"""Disposable synthetic load diagnostics with bounded CPU/DB/JVM profiles.

Never targets existing containers, named volumes, cloud resources or user data.
Receipts are retained on failure. No acceptance threshold is weakened by a retry.
"""
import argparse
import datetime
import base64
import hashlib
import hmac
import http.client
import importlib.util
import json
import os
from pathlib import Path
import secrets
import shutil
import statistics
import subprocess
import sys
import tempfile
import time
import urllib.request
import urllib.error
import urllib.parse
import uuid

ROOT=Path(__file__).resolve().parents[1]
def encoded(value): return json.dumps(value,sort_keys=True,separators=(",", ":")).encode()
class Encoding:
    encoded=staticmethod(encoded)
    digest=staticmethod(lambda value: hashlib.sha256(encoded(value)).hexdigest())
worker=Encoding()

def sha(path): return hashlib.sha256(Path(path).read_bytes()).hexdigest()
def call(args,**kw): return subprocess.run(args,check=True,text=True,encoding="utf-8",capture_output=True,timeout=300,**kw).stdout.strip()

def parse_lag(raw):
    rows=[]
    for line in raw.splitlines():
        fields=line.split()
        if len(fields)>=6 and fields[0]=='ledgerline-projection' and all(value.isdigit() for value in fields[2:6]):
            rows.append({'topic':fields[1],'partition':int(fields[2]),'committed_offset':int(fields[3]),'log_end_offset':int(fields[4]),'lag':int(fields[5])})
    return rows

def analyze_jfr(events):
    from collections import Counter
    counts=Counter(); frames={}
    for event in events:
        kind=event['type']; counts[kind]+=1
        stack=(event.get('values',{}).get('stackTrace') or {}).get('frames') or []
        if stack:
            method=stack[0].get('method',{}); owner=method.get('type',{}).get('name','unknown')
            frames.setdefault(kind,Counter())[owner+'.'+method.get('name','unknown')]+=1
    return {'event_counts':dict(counts),'top_sampled_frames':{kind:counter.most_common(15) for kind,counter in frames.items()},'interpretation':'Sample counts are not latency distributions. Zero contention/socket events are valid observations; missing CPU samples invalidate CPU profiling.'}

def export_jfr(execute,label):
    import re
    assert re.fullmatch(r'[a-z0-9-]+',label),'Invalid fixed scenario label'
    path='/tmp/'+label+'.jfr'; repository='/tmp/ll-jfr-'+label
    diagnostic={'method':'jcmd_dump_base64','repository':repository}
    dump=execute('jcmd','1','JFR.dump','name=benchmark','filename='+path)
    diagnostic['dump_stdout']=dump
    size=int(execute('wc','-c',path).split()[0]); diagnostic['dump_bytes']=size
    if not size:
        # Some container tmpfs runtimes reject the JDK optimized recording copy.
        # Dump still rotates/finalizes chunks. Export only independently valid chunks.
        candidates=sorted(execute('find',repository,'-type','f','-name','*.jfr').splitlines())
        complete=[]; checks=[]
        for chunk in candidates:
            assert chunk.startswith(repository+'/') and re.fullmatch(r'[a-zA-Z0-9_./-]+',chunk),'Unexpected repository path'
            try:
                summary=execute('jfr','summary',chunk)
                valid='Version:' in summary and 'Chunks:' in summary
                checks.append({'path':chunk,'valid':valid,'summary':summary})
                if valid: complete.append(chunk)
            except subprocess.CalledProcessError as error:
                checks.append({'path':chunk,'valid':False,'stderr':(error.stderr or '')[:500]})
        diagnostic['repository_chunks']=checks
        assert complete,'No valid completed JFR repository chunks; dump='+dump[:300]
        # Validated paths contain only fixed ASCII path characters; ordinary cat avoids
        # the failing optimized FileChannel copy. A JFR file is concatenated chunks.
        execute('sh','-c','cat '+' '.join(complete)+' > '+path)
        diagnostic['method']='validated_repository_chunks_base64'
    encoded=execute('base64',path)
    data=base64.b64decode(''.join(encoded.split()),validate=True)
    assert 0<len(data)<=64*1024*1024,'Empty/oversized JFR export'
    assert data[:4]==b'FLR\x00','Invalid JFR file magic'
    diagnostic['exported_bytes']=len(data)
    diagnostic['stop_stdout']=execute('jcmd','1','JFR.stop','name=benchmark')
    return data,diagnostic

class Lab:
    def __init__(self,args):
            self.args=args; self.project="llbench-"+uuid.uuid4().hex[:12]
            self.evidence=args.evidence.resolve(); self.evidence.mkdir(parents=True,exist_ok=False)
            self.private=Path(tempfile.mkdtemp(prefix=self.project+"-"))
            shutil.copyfile(ROOT/"deploy/benchmark-lab.yaml",self.private/"lab-compose.yaml")

            (self.private/"bench").mkdir()
            for name in ("k6-cloud-load.js","k6-many-account-lab.js"):
                shutil.copyfile(ROOT/"bench"/name,self.private/"bench"/name)
            self.started=time.monotonic(); self.latencies=[]; self.errors=0; self.attempts=0; self.steps={}; self.recovery_cycles=0
            self.http_receipts=[]; self.http_phase="setup"
            self.config={"LAB_ARTIFACTS":self.private.as_posix(),"LAB_DB_PASSWORD":secrets.token_hex(24),
                "LAB_READER_PASSWORD":secrets.token_hex(24),"LAB_JWT_SECRET":secrets.token_hex(32),"LAB_LEDGER_CPUS":str(args.ledger_cpus),
                "LAB_POSTGRES_CPUS":str(args.postgres_cpus),"LAB_KAFKA_CPUS":str(args.kafka_cpus),
                "LAB_LEDGER_POOL_SIZE":str(args.ledger_pool_size)}
            images=json.loads((ROOT/"deploy/benchmark-images.json").read_text())
            for k,v in images.items():
                assert "@sha256:" in v,"Image must be pinned by registry digest"
                try: inspected=json.loads(call(["docker","image","inspect",v]))[0]
                except subprocess.CalledProcessError:
                    call(["docker","pull",v]); inspected=json.loads(call(["docker","image","inspect",v]))[0]
                self.config[k]=inspected["Id"]
            self.image_references=images
            self.envfile=self.private/"lab.env"; self.envfile.write_text("\n".join(k+"="+v for k,v in self.config.items())+"\n")
            self.compose=["docker","compose","--env-file",str(self.envfile),"-f",str(self.private/"lab-compose.yaml"),"-p",self.project]
            assert not self.dc("ps","-aq"),"Refuse existing fixture identity"
            candidate=json.loads(args.candidate.read_text())
            workspace=Path(candidate["workspace"])
            for name,expected in candidate["source_sha256"].items():
                assert sha(workspace/name)==expected,"Candidate source changed since manifest: "+name
            for module in ("ledger-service","projection-service"):
                artifact=workspace/module/"target"/(module+"-1.0.0-SNAPSHOT.jar")
                assert sha(artifact)==candidate["artifact_sha256"][module],"Artifact differs from tested manifest"
                shutil.copyfile(artifact,self.private/(module+".jar"))
            self.source=worker.digest(candidate["source_sha256"])
            self.operator=self.token("OPERATOR"); self.reader=self.token("RECONCILER"); self.customer=self.token("CUSTOMER")
            self.identity={"project":self.project,"source_manifest_sha256":self.source,"candidate_identity":str(args.candidate.resolve()),
                "images":{k:v for k,v in self.config.items() if k.endswith("_IMAGE")},
                "artifacts":{m:sha(self.private/(m+".jar")) for m in ("ledger-service","projection-service")},
                "compose_sha256":sha(ROOT/"deploy/benchmark-lab.yaml"),"image_references":self.image_references,
                "harness_sha256":sha(Path(__file__)),
                "original_load_sha256":sha(ROOT/"bench/k6-cloud-load.js"),"many_account_load_sha256":sha(ROOT/"bench/k6-many-account-lab.js"),
                "profile":args.profile,"scenario":args.scenario,"duration_required_seconds":0,
                "scope":"isolated synthetic local load diagnostics; no recovery acceptance",
                "private_artifact_directory":str(self.private),
                "budgets":{"postgres_memory_mib":600,"kafka_memory_mib":700,"ledger_memory_mib":768,"projection_memory_mib":640,
                    "logs_per_service_mib":20,"postgres_disk_mib":2048,"ledger_cpus":args.ledger_cpus,"postgres_cpus":args.postgres_cpus,"kafka_cpus":args.kafka_cpus,"projection_cpus":0.75,"original_p95_ms":750,"original_p99_ms":1500,"unexpected_error_fraction":0.01,
                    "ledger_pool_size":args.ledger_pool_size,
                    "convergence_seconds":180,"process_stop_seconds":45,"dependency_recovery_seconds":180},
                "stop_decision":"Stop and record FAIL on invariant/authority violation, unrecovered dependency, resource ceiling, or unexplained error; preserve receipts. No simulated elapsed time."}
            self.write("identity.json",self.identity)
            shutil.copyfile(ROOT/"deploy/benchmark-lab.yaml",self.evidence/"lab-compose.yaml")
            self.write("effective-config.json",{"ports":"loopback random","network":"internal DB/Kafka network; app services also attach to loopback-published edge bridge","read_only_filesystem":True,"restart":"on-failure:3","model":"none","limits":self.identity["budgets"]})

    def token(self,role):
            def b(v): return base64.urlsafe_b64encode(worker.encoded(v)).decode().rstrip("=")
            data=b({"alg":"HS256","typ":"JWT"})+"."+b({"sub":"synthetic-lab-"+role.lower(),"roles":[role],"exp":int(time.time())+90000})
            sig=base64.urlsafe_b64encode(hmac.new(self.config["LAB_JWT_SECRET"].encode(),data.encode(),hashlib.sha256).digest()).decode().rstrip("=")
            return data+"."+sig

    def write(self,name,value): (self.evidence/name).write_text(json.dumps(value,indent=2)+"\n",encoding="utf-8")

    def dc(self,*args,**kw): return call(self.compose+list(args),**kw)

    def sql(self,sql,database="ledgerline"):
            return self.dc("exec","-T","postgres","psql","-X","-U","lab_owner","-d",database,"-v","ON_ERROR_STOP=1","-At",input=sql)

    def request(self,base,path,body=None,key=None,token=None,expected=200):
            headers={"Authorization":"Bearer "+(token or self.operator),"Content-Type":"application/json"}
            if key: headers["Idempotency-Key"]=key
            req=urllib.request.Request(base+path,data=None if body is None else worker.encoded(body),headers=headers)
            start=time.monotonic()
            try:
                with urllib.request.urlopen(req,timeout=10) as r:
                    code=r.status; raw=r.read(32769); correlation=r.headers.get("X-Correlation-ID")
                assert len(raw)<=32768,"Oversized lab response"
                value=json.loads(raw)
            except urllib.error.HTTPError as e: code=e.code; value={}; correlation=None
            except (urllib.error.URLError,TimeoutError,OSError):
                # No response is an uncertain commit, not a confirmed rollback.
                code=0; value={}; correlation=None
            if path=="/api/v1/transfers":
                duration=(time.monotonic()-start)*1000
                self.attempts+=1; self.latencies.append(duration)
                self.http_receipts.append({"elapsed_seconds":time.monotonic()-self.started,"duration_ms":duration,"status":code,"expected":expected,"phase":self.http_phase,
                    "idempotency_key":key,"outcome":"uncertain_no_response" if code==0 else "response_observed"})
                if code!=expected: self.errors+=1
            assert code==expected,f"Unexpected status {code}, expected {expected}, path={path}"
            return value,correlation

    def wait_health(self,base):
            # Docker reallocates ephemeral published ports on container start.
            # Refresh the observed address, never reuse a stale pre-restart socket.
            service="ledger-service" if base==self.ledger else "projection-service"
            base="http://"+self.dc("port",service,"8080" if service=="ledger-service" else "8081")
            assert base.startswith("http://127.0.0.1:"),"Rebound API port is not loopback"
            if service=="ledger-service": self.ledger=base
            else: self.projection=base
            deadline=time.monotonic()+240
            while time.monotonic()<deadline:
                try:
                    with urllib.request.urlopen(base+"/actuator/health",timeout=3) as r:
                        if json.load(r).get("status")=="UP": return
                except Exception: pass
                time.sleep(2)
            raise AssertionError("Health timeout")

    def summaries(self):
            l,_=self.request(self.ledger,"/api/v1/ops/reconciliation",token=self.reader)
            p,_=self.request(self.projection,"/api/v1/ops/reconciliation",token=self.reader)
            return {"ledger_summary":l,"projection_summary":p}

    def reconcile(self,settle=True):
            start=time.monotonic(); deadline=start+180
            while True:
                data=self.summaries(); l=data["ledger_summary"]; p=data["projection_summary"]
                oldest=float(self.sql("SELECT coalesce(extract(epoch FROM clock_timestamp()-min(o.created_at)),0) FROM outbox o LEFT JOIN processed_events e ON e.event_id=(o.payload->>'eventId')::uuid WHERE e.event_id IS NULL;"))
                sample={"elapsed_seconds":time.monotonic()-self.started,"convergence_seconds":time.monotonic()-start,"aggregates":data,"oldest_unprojected_seconds":oldest}
                with (self.evidence/"reconciliation.jsonl").open("a",encoding="utf-8") as f: f.write(json.dumps(sample)+"\n")
                for k in ("unbalanced_transactions","balance_drift","negative_protected"): assert l[k]==0,(k,l[k])
                for k in ("statement_drift","summary_drift","quarantine_pending"): assert p[k]==0,(k,p[k])
                if l["outbox_pending"]==0 and l["ledger_postings"]==p["projection_postings"] and p["consumer_lag_known"]==1 and p["consumer_lag"]==0: break
                if not settle:
                    assert oldest<=180 and l["outbox_oldest_seconds"]<=180,"Steady pipeline age exceeded 180-second budget"
                    assert p["consumer_lag_known"]==1,"Steady broker lag observation unavailable"
                if not settle: break
                assert time.monotonic()<deadline,"Convergence exceeded original 180-second gate"
                time.sleep(1)
            return data

    def transfer(self,a,b,key=None,expected=201,amount=1):
            return self.request(self.ledger,"/api/v1/transfers",{"fromAccountId":a,"toAccountId":b,"amountMinor":amount,"currency":"USD"},key or "lab-"+uuid.uuid4().hex,expected=expected)

    def sample_resources(self):
            ids=self.dc("ps","-q").splitlines()
            raw=call(["docker","stats","--no-stream","--format","{{json .}}",*ids])
            with (self.evidence/"resources.jsonl").open("a",encoding="utf-8") as f:
                for line in raw.splitlines(): f.write(json.dumps({"elapsed_seconds":time.monotonic()-self.started,"docker":json.loads(line)})+"\n")
            size=int(self.dc("exec","-T","postgres","du","-sk","/var/lib/postgresql/data").split()[0])
            assert size<2048*1024,"PostgreSQL disk ceiling"
            config=json.loads(call(["docker","inspect",*ids]))
            assert not any(x["State"].get("OOMKilled") for x in config),"Fixture OOM"

    def setup(self):
            self.dc("up","-d")
            self.ledger="http://"+self.dc("port","ledger-service","8080")
            self.projection="http://"+self.dc("port","projection-service","8081")
            assert self.ledger.startswith("http://127.0.0.1:") and self.projection.startswith("http://127.0.0.1:"),"Loopback ports were not published"
            self.wait_health(self.ledger); self.wait_health(self.projection)
            password=self.config["LAB_READER_PASSWORD"]
            # Fixed harness SQL; no model or external input chooses SQL or authority.
            self.sql((ROOT/"deploy/reconciliation-role.sql").read_text()+
                "\nCREATE ROLE lab_reader LOGIN PASSWORD '"+password+"' CONNECTION LIMIT 2;\n"+
                "GRANT ledgerline_reconciliation_reader TO lab_reader;\nALTER ROLE lab_reader SET statement_timeout='2000ms';\nALTER ROLE lab_reader SET default_transaction_read_only=on;")
            self.request(self.ledger,"/api/v1/ops/reconciliation",token=self.customer,expected=403)
            self.request(self.projection,"/api/v1/ops/reconciliation",token=self.customer,expected=403)
            self.request(self.ledger,"/api/v1/accounts",body={"currency":"USD","type":"CUSTOMER"},token=self.reader,expected=403)
            self.accounts=[]
            overdraft,_=self.request(self.ledger,"/api/v1/accounts",body={"currency":"USD","type":"OVERDRAFT"},expected=201)
            for _ in range(20):
                account,_=self.request(self.ledger,"/api/v1/accounts",body={"currency":"USD","type":"CUSTOMER"},expected=201)
                self.accounts.append(account["id"])
                self.transfer(overdraft["id"],account["id"],amount=10000000)
            self.reconcile(); self.sample_resources()
            self.steps["identity_and_authorization"]="PASS"
            self.write("fixture.json",{"accounts":self.accounts,"label":"synthetic lab only","ledger":self.ledger,"projection":self.projection})
            self.sql("CREATE EXTENSION IF NOT EXISTS pg_stat_statements; SELECT pg_stat_statements_reset();")
            assert self.sql("SELECT current_setting('fsync') || '/' || current_setting('synchronous_commit') || '/' || current_setting('full_page_writes');")=="on/on/on","Durability settings must remain enabled"

    def load(self,many=False):
            network=self.project+"_lab"; label="many-account" if many else "original-two-account"
            env={**os.environ,"ACCESS_TOKEN":self.operator,"ACCOUNT_A":self.accounts[0],"ACCOUNT_B":self.accounts[1],
                "ACCOUNT_IDS":json.dumps(self.accounts),"CLOUD_URL":"http://ledger-service:8080/api/v1","K6_PROFILE":"performance",
                "K6_SUMMARY_TREND_STATS":"avg,min,med,max,p(50),p(95),p(99)"}
            load_container=self.project+"-load-"+label
            cmd=["docker","run","--rm","--name",load_container,"--label","com.docker.compose.project="+self.project,"--network",network,"--memory=256m","--cpus=0.5",
                "--mount",f"type=bind,source={self.private/'bench'},target=/bench,readonly",
                "--mount",f"type=bind,source={self.evidence},target=/results"]
            for name in ("ACCESS_TOKEN","ACCOUNT_A","ACCOUNT_B","ACCOUNT_IDS","CLOUD_URL","K6_PROFILE","K6_SUMMARY_TREND_STATS"): cmd += ["-e",name]
            cmd += [self.config["LAB_K6_IMAGE"],"run","--summary-export",f"/results/{label}.json","/bench/"+("k6-many-account-lab.js" if many else "k6-cloud-load.js")]
            self.profile_start(label)
            with (self.evidence/(label+".log")).open("w",encoding="utf-8") as log:
                process=subprocess.Popen(cmd,env=env,stdout=log,stderr=subprocess.STDOUT)
                deadline=time.monotonic()+300; next_sample=0; next_agent=0
                while process.poll() is None:
                    now=time.monotonic()
                    if now>deadline:
                        call(["docker","stop","--time","5",load_container]); process.wait(timeout=15)
                        raise AssertionError("Bounded load runner timed out")
                    if now>=next_sample:
                        self.sample_resources(); self.profile_sample(label)
                        waits=self.sql("SELECT coalesce(wait_event_type,'CPU'),coalesce(wait_event,'running'),count(*) FROM pg_stat_activity WHERE datname=current_database() GROUP BY 1,2 ORDER BY 3 DESC;")
                        # Aggregate only: never retain query text, keys or payloads.
                        contention=self.sql("""SELECT json_build_object(
                            'active',count(*) FILTER (WHERE state='active'),
                            'blocked',count(*) FILTER (WHERE cardinality(pg_blocking_pids(pid))>0),
                            'balance_lock_waiters',count(*) FILTER (WHERE wait_event_type='Lock' AND query LIKE '%FROM account_balances%'),
                            'oldest_transaction_seconds',coalesce(max(extract(epoch FROM clock_timestamp()-xact_start)),0))
                            FROM pg_stat_activity WHERE datname=current_database() AND pid<>pg_backend_pid();""")
                        with (self.evidence/"db-waits.jsonl").open("a",encoding="utf-8") as f:
                            f.write(json.dumps({"scenario":label,"elapsed_seconds":now-self.started,"waits":waits,"contention":json.loads(contention)})+"\n")
                        next_sample=now+10
                    if now>=next_agent:
                        next_agent=now+90
                    time.sleep(2)
                code=process.returncode
            self.profile_finish(label)
            self.steps[label]={"status":"PASS" if code==0 else "FAIL","exit_code":code,"scope":"diagnostics; no recovery/restore acceptance"}
            try: self.reconcile()
            except AssertionError as e:
                if str(e)!="Convergence exceeded original 180-second gate": raise
                self.steps[label]["status"]="FAIL"; self.steps[label]["convergence_gate"]="FAIL"
                self.reconcile() # Separate bounded settling; cannot repair original FAIL.
                self.steps[label]["additional_settle"]="Observed later; original gate remains FAIL"
            # Preserve failed attempts; no threshold/config changes to reclassify them.
            return self.steps[label]["status"]=="PASS"
    def observed(self, name, action):
        try: return {"status":"PASS","value":action()}
        except Exception as error:
            return {"status":"UNVERIFIED","error":type(error).__name__+": "+str(error)[:300],"stderr":getattr(error,"stderr",None)[:500] if getattr(error,"stderr",None) else None}

    def cgroups(self):
        result={}
        for service in ("postgres","kafka","ledger-service","projection-service"):
            def capture():
                command="if test -f /sys/fs/cgroup/cpu.stat; then echo V2; cat /sys/fs/cgroup/cpu.stat; echo MEMORY_CURRENT; cat /sys/fs/cgroup/memory.current; echo MEMORY_EVENTS; cat /sys/fs/cgroup/memory.events; else echo V1; cat /sys/fs/cgroup/cpu/cpu.stat; echo CPU_USAGE_NS; cat /sys/fs/cgroup/cpuacct/cpuacct.usage; echo MEMORY_CURRENT; cat /sys/fs/cgroup/memory/memory.usage_in_bytes; echo MEMORY_EVENTS; printf 'failcnt '; cat /sys/fs/cgroup/memory/memory.failcnt; fi"
                raw=self.dc("exec","-T",service,"sh","-c",command)
                version,raw=raw.split("\n",1)
                cpu,rest=raw.split("MEMORY_CURRENT\n"); memory,events=rest.split("MEMORY_EVENTS\n")
                usage=None
                if version=='V1': cpu,usage=cpu.split('CPU_USAGE_NS\n')
                counters={k:int(v) for k,v in (line.split() for line in cpu.splitlines())}
                if version=='V1':
                    counters['usage_usec']=int(usage.strip())/1000
                    counters['throttled_usec']=counters.pop('throttled_time')/1000
                return {"cgroup_version":version,"cpu_time_unit":"microseconds","cpu":counters,"memory_current_bytes":int(memory.strip()),"memory_events":{k:int(v) for k,v in (line.split() for line in events.splitlines())}}
            result[service]=self.observed(service,capture)
        return result

    def profile_start(self,label):
        self.sql("SELECT pg_stat_statements_reset();")
        self.cpu_start=self.cgroups()
        self.write(label+"-cpu-start.json",self.cpu_start)
        self.write(label+"-host.json",{"docker_info":json.loads(call(["docker","info","--format","{{json .}}"])),"containers":json.loads(call(["docker","inspect",*self.dc("ps","-q").splitlines()])),"durability":self.sql("SELECT json_build_object('fsync',current_setting('fsync'),'synchronous_commit',current_setting('synchronous_commit'),'full_page_writes',current_setting('full_page_writes'),'track_io_timing',current_setting('track_io_timing'));"),"note":"Docker inspection contains generated synthetic fixture credentials. Keep raw receipts private; publish sanitized report only."})
        self.jfr_status={}
        for service in ("ledger-service","projection-service"):
            def start(service=service):
                configured=self.dc("exec","-T",service,"jcmd","1","JFR.configure","repositorypath=/tmp/ll-jfr-"+label)
                started=self.dc("exec","-T",service,"jcmd","1","JFR.start","name=benchmark","settings=profile","disk=true","maxsize=64m")
                assert 'Started recording' in started,'JFR did not start'
                return {'configure':configured,'start':started}
            self.jfr_status[service]=self.observed(service,start)
        self.phase_start=self.profile_sample(label)
        self.write(label+"-profile-start.json",self.phase_start)

    def profile_sample(self,label):
        metrics={}
        names=("ledger.transfer.phase","hikaricp.connections.acquire","hikaricp.connections.pending","hikaricp.connections.active","hikaricp.connections.idle","tomcat.threads.current","tomcat.threads.busy","jvm.threads.live","jvm.memory.used","process.cpu.usage","jvm.gc.pause")
        for name in names:
            metrics[name]=self.observed(name,lambda name=name:self.request(self.ledger,"/actuator/metrics/"+name)[0])
        for phase in ("transaction_start","key_lock","replay_check","metadata_lock","balance_lock","balance_lock_to_completion","persist","commit_cleanup","total_transaction"):
            metrics["phase."+phase]=self.observed(phase,lambda phase=phase:self.request(self.ledger,"/actuator/metrics/ledger.transfer.phase?tag=phase:"+phase)[0])
        database=self.observed("database",lambda:json.loads(self.sql("""SELECT json_build_object(
            'connections',(SELECT count(*) FROM pg_stat_activity WHERE datname=current_database()),
            'active',(SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND state='active'),
            'blocked',(SELECT count(*) FROM pg_stat_activity WHERE cardinality(pg_blocking_pids(pid))>0),
            'connection_limit',current_setting('max_connections'),
            'waits',(SELECT coalesce(json_agg(w),'[]'::json) FROM (SELECT state,wait_event_type,wait_event,count(*) FROM pg_stat_activity WHERE datname=current_database() GROUP BY 1,2,3) w));""")))
        lag=self.observed("kafka_committed_offset_lag",lambda:self.dc("exec","-T","kafka","/opt/kafka/bin/kafka-consumer-groups.sh","--bootstrap-server","kafka:9092","--describe","--group","ledgerline-projection"))
        # CLI reports CURRENT-OFFSET, LOG-END-OFFSET and LAG per partition. No offset mutation.
        threads=self.observed("thread_dump",lambda:self.dc("exec","-T","ledger-service","jcmd","1","Thread.print","-l"))
        row={"scenario":label,"elapsed_seconds":time.monotonic()-self.started,"metrics":metrics,"database":database,"kafka_committed_offsets":lag,"cgroups":self.cgroups(),"threads":threads}
        with (self.evidence/"profiles.jsonl").open("a",encoding="utf-8") as output: output.write(json.dumps(row)+"\n")
        return row

    def profile_finish(self,label):
        final=self.profile_sample(label)
        self.write(label+"-profile-end.json",final)
        deltas={}
        for key,before in self.phase_start['metrics'].items():
            after=final['metrics'][key]
            if before['status']!='PASS' or after['status']!='PASS': continue
            start={item['statistic']:item['value'] for item in before['value'].get('measurements',[])}
            end={item['statistic']:item['value'] for item in after['value'].get('measurements',[])}
            if 'COUNT' in start and 'TOTAL_TIME' in start:
                count=end['COUNT']-start['COUNT']; total=end['TOTAL_TIME']-start['TOTAL_TIME']
                deltas[key]={'count':count,'total_seconds':total,'mean_ms':total/count*1000 if count>0 else None,'final_max_seconds':end.get('MAX'),'note':'MAX is final rolling timer maximum, not a subtracted or exact workload maximum.'}
        self.write(label+'-timer-deltas.json',{'boundaries':'immediately before k6 starts / immediately after exit before reconciliation','metrics':deltas,'interpretation':'Means use count/sum deltas only. Phase means are not percentiles. HTTP latency includes additional authentication/controller/network work.'})
        end=self.cgroups(); deltas={}
        for service,before in self.cpu_start.items():
            if before['status']=='PASS' and end[service]['status']=='PASS':
                deltas[service]={k:end[service]['value']['cpu'][k]-v for k,v in before['value']['cpu'].items()}
            else: deltas[service]={"status":"UNVERIFIED"}
        self.write(label+"-cpu-deltas.json",{"start":self.cpu_start,"end":end,"deltas":deltas})
        query=self.observed("query_latency",lambda:json.loads(self.sql("""SELECT coalesce(json_agg(s),'[]'::json) FROM (
            SELECT queryid::text,query,calls,total_exec_time,mean_exec_time,min_exec_time,max_exec_time,stddev_exec_time,
                   rows,shared_blks_hit,shared_blks_read,shared_blks_dirtied,shared_blks_written,blk_read_time,blk_write_time
            FROM pg_stat_statements WHERE dbid=(SELECT oid FROM pg_database WHERE datname=current_database()) AND calls>0
            ORDER BY total_exec_time DESC LIMIT 50) s;""")))
        self.write(label+"-queries.json",query)
        for service in self.jfr_status:
            def finish(service=service):
                path="/tmp/"+label+".jfr"
                target=self.evidence/(label+"-"+service+".jfr")
                diagnostic_path=self.evidence/(label+"-"+service+"-jfr-export.json")
                def execute(*arguments): return self.dc("exec","-T",service,*arguments)
                payload,export=export_jfr(execute,label)
                self.write(diagnostic_path.name,export)
                target.write_bytes(payload)
                summary=self.dc("exec","-T",service,"jfr","summary",path)
                (self.evidence/(label+"-"+service+"-jfr-summary.txt")).write_text(summary,encoding="utf-8")
                # Text captures CPU samples, monitor blocking and parking stacks without requiring a GUI.
                stacks=self.dc("exec","-T",service,"jfr","print","--events","jdk.ExecutionSample,jdk.JavaMonitorEnter,jdk.SocketRead,jdk.ThreadPark,jdk.CPULoad",path)
                (self.evidence/(label+"-"+service+"-jfr-stacks.txt")).write_text(stacks,encoding="utf-8")
                event_json=self.dc("exec","-T",service,"jfr","print","--json","--events","jdk.ExecutionSample,jdk.JavaMonitorEnter,jdk.SocketRead,jdk.ThreadPark",path)
                events=json.loads(event_json)['recording']['events']
                analysis=analyze_jfr(events)
                self.write(label+"-"+service+"-jfr-analysis.json",analysis)
                assert analysis['event_counts'].get('jdk.ExecutionSample',0)>0,"No JFR CPU samples captured"
                return {"sha256":sha(target),"bytes":target.stat().st_size,"analysis":analysis,"export":export}
            self.jfr_status[service]["finish"]=self.observed(service,finish)
        self.write(label+"-jfr.json",self.jfr_status)
        rows=[json.loads(line) for line in (self.evidence/'profiles.jsonl').read_text().splitlines() if line.strip()]
        rows=[row for row in rows if row['scenario']==label]
        required=('ledger.transfer.phase','hikaricp.connections.acquire','hikaricp.connections.pending','hikaricp.connections.active','tomcat.threads.current','tomcat.threads.busy','jvm.threads.live','jvm.memory.used','process.cpu.usage','jvm.gc.pause')
        missing=[]
        for metric in required:
            if not any(row['metrics'][metric]['status']=='PASS' for row in rows): missing.append(metric)
        for phase in ("transaction_start","key_lock","replay_check","metadata_lock","balance_lock","balance_lock_to_completion","persist","commit_cleanup","total_transaction"):
            if not any(row['metrics']['phase.'+phase]['status']=='PASS' and any(m['statistic']=='COUNT' and m['value']>0 for m in row['metrics']['phase.'+phase]['value']['measurements']) for row in rows): missing.append('phase.'+phase)
        for key in ('database','threads'):
            if not rows or not all(row[key]['status']=='PASS' for row in rows): missing.append(key)
        lag_samples=[]
        for row in rows:
            observed=row['kafka_committed_offsets']
            lag_samples.append(parse_lag(observed.get('value','')) if observed['status']=='PASS' else [])
        if not lag_samples or not all(lag_samples): missing.append('kafka_committed_offset_lag')
        if query['status']!='PASS' or not query.get('value'): missing.append('pg_stat_statements')
        for service in self.jfr_status:
            if self.jfr_status[service]['status']!='PASS' or self.jfr_status[service].get('finish',{}).get('status')!='PASS': missing.append('jfr.'+service)
        if any(value.get('status')=='UNVERIFIED' for value in deltas.values()): missing.append('cgroup_cpu_deltas')
        self.write(label+'-profiling-status.json',{'status':'PASS' if not missing else 'UNVERIFIED','missing':missing,'sample_count':len(rows),'committed_offset_samples':lag_samples,'note':'Profile validity does not establish root cause or performance acceptance.'})

    def cleanup(self):
        errors=[]
        try: self.dc("down","--volumes","--remove-orphans")
        except Exception as error: errors.append(str(error)[:500])
        remaining={}
        for kind,cmd in {"containers":["docker","ps","-aq"],"volumes":["docker","volume","ls","-q"],"networks":["docker","network","ls","-q"]}.items():
            try: remaining[kind]=call(cmd+["--filter","label=com.docker.compose.project="+self.project]).splitlines()
            except Exception as error: errors.append(str(error)[:500]); remaining[kind]=["UNKNOWN"]
        result={"status":"PASS" if not errors and not any(remaining.values()) else "FAIL","project":self.project,"remaining":remaining,"errors":errors}
        self.write("cleanup.json",result)
        return result["status"]=="PASS"

    def run(self):
        failure=None
        try:
            self.setup()
            scenarios=[False,True] if self.args.scenario=="both" else [self.args.scenario=="many"]
            passed=[self.load(many) for many in scenarios]
            if not all(passed): failure="Original load/convergence threshold failed; diagnostic receipts preserved"
        except BaseException as error: failure=type(error).__name__+": "+str(error)[:500]
        finally:
            try: (self.evidence/"services.log").write_text(self.dc("logs","--no-color","--tail","3000"),encoding="utf-8")
            except Exception: pass
            profile_files=list(self.evidence.glob('*-profiling-status.json'))
            profiling='PASS' if profile_files and all(json.loads(path.read_text())['status']=='PASS' for path in profile_files) else 'UNVERIFIED'
            clean=self.cleanup()
            if not clean: failure=(failure or "")+"; fixture cleanup failed"
            result={"status":"FAIL" if failure else "PASS","failure":failure,"steps":self.steps,"actual_total_seconds":time.monotonic()-self.started,"profiling_status":profiling,"scope":"bounded synthetic load diagnostics only; recovery, restore, soak and production acceptance UNVERIFIED"}
            self.write("result.json",result)
            self.write("SHA256.json",{str(p.relative_to(self.evidence)):sha(p) for p in self.evidence.rglob('*') if p.is_file() and p.name!='SHA256.json'})
        print(json.dumps(result),flush=True)
        return int(bool(failure))


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--candidate',required=True,type=Path,help='Frozen source/artifact manifest from benchmark-manifest.py; tests require separate receipts')
    parser.add_argument('--evidence',required=True,type=Path,help='New evidence directory; never reused')
    parser.add_argument('--scenario',choices=('original','many','both'),default='original')
    parser.add_argument('--ledger-pool-size',type=int,default=12)
    parser.add_argument('--ledger-cpus',type=float,default=1.0)
    parser.add_argument('--postgres-cpus',type=float,default=1.0)
    parser.add_argument('--kafka-cpus',type=float,default=.5)
    args=parser.parse_args(); args.profile='load-only'
    if not 2<=args.ledger_pool_size<=30: parser.error('pool size must be 2..30')
    if not .5<=args.ledger_cpus<=2 or not .5<=args.postgres_cpus<=2 or not .5<=args.kafka_cpus<=1: parser.error('CPU values outside bounded lab range')
    return Lab(args).run()

if __name__=='__main__': raise SystemExit(main())
