"""Optional real HTTP/outbox/Kafka/projection OTLP smoke; never runs load or soak."""
import argparse
import importlib.util
import json
from pathlib import Path
import shutil
import time
import urllib.request
import uuid

ROOT=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('benchmark_lab',ROOT/'scripts/benchmark-lab.py')
base=importlib.util.module_from_spec(spec); spec.loader.exec_module(base)
PYTHON_IMAGE='python@sha256:09f7da3bc104798d0afb40bc08d23ab2da20a76130cec1f2ef170848f5d85217'

def verify_chain(spans,trace_id,external_parent,http_parent):
    relevant=[span for span in spans if span['trace_id']==trace_id]
    def unique(name):
        matches=[span for span in relevant if span['name']==name]
        assert len(matches)==1,'Expected one '+name+' span, observed '+str(len(matches))
        return matches[0]
    http,publish,projection=unique('http.request'),unique('outbox.publish'),unique('projection.process')
    assert http['parent_span_id']==external_parent,'HTTP did not preserve supplied parent'
    assert http['span_id']==http_parent.split('-')[2],'Response traceparent differs from exported HTTP span'
    assert publish['parent_span_id']==http['span_id'],'Outbox span lost HTTP parent'
    assert projection['parent_span_id']==publish['span_id'],'Projection span lost Kafka producer parent'
    assert (http['kind'],publish['kind'],projection['kind'])==(2,4,5),'Unexpected server/producer/consumer span kinds'
    assert http['service']==publish['service'] and projection['service']!=http['service'],'Expected two service resources'
    return {'trace_id':trace_id,'chain':[http,publish,projection],'status':'PASS','scope':'Actual SDK-exported spans received over OTLP/HTTP; single-event settled batch'}

class TraceLab(base.Lab):
    def __init__(self,args):
        super().__init__(args)
        try: inspected=json.loads(base.call(['docker','image','inspect',PYTHON_IMAGE]))[0]
        except Exception:
            base.call(['docker','pull',PYTHON_IMAGE]); inspected=json.loads(base.call(['docker','image','inspect',PYTHON_IMAGE]))[0]
        shutil.copyfile(ROOT/'scripts/trace-otlp-receiver.py',self.private/'receiver.py')
        overlay=self.private/'trace-compose.yaml'
        overlay.write_text('''services:
  telemetry:
    image: '''+inspected['Id']+'''
    pull_policy: never
    command: [python, -B, /app/receiver.py]
    user: "10001:10001"
    read_only: true
    tmpfs: [/tmp]
    mem_limit: 128m
    cpus: 0.25
    pids_limit: 32
    networks: [lab, edge]
    ports: ["127.0.0.1::4318"]
    volumes: ["'''+self.private.as_posix()+'''/receiver.py:/app/receiver.py:ro"]
  ledger-service:
    environment:
      LEDGERLINE_TELEMETRY_ENDPOINT: http://telemetry:4318/v1/traces
      LEDGERLINE_TELEMETRY_SAMPLE_RATIO: "1.0"
  projection-service:
    environment:
      LEDGERLINE_TELEMETRY_ENDPOINT: http://telemetry:4318/v1/traces
      LEDGERLINE_TELEMETRY_SAMPLE_RATIO: "1.0"
''',encoding='utf-8')
        self.compose+=['-f',str(overlay)]
        shutil.copyfile(overlay,self.evidence/'trace-compose.yaml')
        self.write('trace-smoke-identity.json',{'receiver_image':PYTHON_IMAGE,'receiver_image_id':inspected['Id'],'receiver_sha256':base.sha(ROOT/'scripts/trace-otlp-receiver.py'),'smoke_sha256':base.sha(Path(__file__)),'overlay_sha256':base.sha(overlay),'scope':'isolated synthetic trace-flow and exporter-outage correctness only; no benchmark/soak'})

    def observed_transfer(self,key,expected=201,trace_id=None,parent=None):
        trace_id=trace_id or uuid.uuid4().hex; parent=parent or uuid.uuid4().hex[:16]
        body={'fromAccountId':self.accounts[0],'toAccountId':self.accounts[1],'amountMinor':1,'currency':'USD'}
        request=urllib.request.Request(self.ledger+'/api/v1/transfers',data=base.encoded(body),headers={'Authorization':'Bearer '+self.operator,'Content-Type':'application/json','Idempotency-Key':key,'traceparent':'00-'+trace_id+'-'+parent+'-01'})
        with urllib.request.urlopen(request,timeout=10) as response:
            assert response.status==expected
            value=json.load(response); observed=response.headers['traceparent']
        assert observed.split('-')[1]==trace_id,'Response lost trace ID'
        return value,observed,trace_id,parent

    def spans(self):
        address='http://'+self.dc('port','telemetry','4318')
        assert address.startswith('http://127.0.0.1:')
        with urllib.request.urlopen(address+'/spans',timeout=3) as response:
            raw=response.read(8*1024*1024+1)
            assert len(raw)<=8*1024*1024,'Receiver output limit'
            return json.loads(raw)

    def run_trace(self):
        failure=None; steps={}
        try:
            self.setup()
            key='trace-smoke-'+uuid.uuid4().hex
            response,http_parent,trace_id,parent=self.observed_transfer(key)
            self.reconcile()
            transaction=response['transactionId']
            stored=self.sql("SELECT payload->>'traceparent' FROM outbox WHERE payload->>'transactionId'='"+transaction+"';")
            assert stored==http_parent,'Durable outbox did not preserve HTTP context'
            deadline=time.monotonic()+30; last=None
            while time.monotonic()<deadline:
                export=self.spans()
                try: chain=verify_chain(export['spans'],trace_id,parent,http_parent); break
                except AssertionError as error: last=error; time.sleep(1)
            else: raise AssertionError('Exporter chain unavailable: '+str(last))
            self.write('otlp-receipts.json',export); self.write('trace-chain.json',chain)
            # Independent read-only consumer observes actual Kafka trace header and payload.
            kafka=self.dc('exec','-T','kafka','/opt/kafka/bin/kafka-console-consumer.sh','--bootstrap-server','kafka:9092','--topic','ledger.transfers.v1','--from-beginning','--max-messages','21','--timeout-ms','15000','--consumer-property','enable.auto.commit=false','--property','print.headers=true','--property','print.value=true')
            matching=[line for line in kafka.splitlines() if transaction in line]
            assert len(matching)==1,'Expected one observed Kafka event'
            producer=chain['chain'][1]
            assert 'traceparent:00-'+trace_id+'-'+producer['span_id']+'-01' in matching[0],'Kafka header differs from actual producer span'
            self.write('kafka-trace-receipt.json',{'line':matching[0],'transaction_id':transaction,'outbox_traceparent':stored,'projection_correlation_count':self.sql("SELECT count(*) FROM projection_trace WHERE transaction_id='"+transaction+"';")})
            assert self.sql("SELECT count(*) FROM projection_trace WHERE transaction_id='"+transaction+"';")=='1'
            steps['actual_exported_cross_service_chain']='PASS'
            before=int(self.sql('SELECT count(*) FROM postings;'))
            self.dc('stop','-t','5','telemetry')
            container=self.dc('ps','-aq','telemetry')
            state=json.loads(base.call(['docker','inspect',container]))[0]['State']
            assert not state['Running'],'Collector outage not actually applied'
            outage_key='trace-outage-'+uuid.uuid4().hex
            committed,_,_,_=self.observed_transfer(outage_key)
            self.reconcile(); time.sleep(3)
            replay,_,_,_=self.observed_transfer(outage_key,expected=200)
            assert replay['transactionId']==committed['transactionId']
            assert int(self.sql('SELECT count(*) FROM postings;'))==before+2,'Exporter outage or replay changed monetary posting count'
            self.reconcile()
            self.write('collector-outage.json',{'status':'PASS','collector_state':state,'transaction_id':committed['transactionId'],'replay_transaction_id':replay['transactionId'],'replay_http_status':200,'posting_delta':2,'note':'No durability guarantee for telemetry during outage; monetary commit, idempotency and projection convergence verified independently.'})
            steps['collector_outage_commit_replay_convergence']='PASS'
        except BaseException as error: failure=type(error).__name__+': '+str(error)[:800]
        finally:
            try: (self.evidence/'services.log').write_text(self.dc('logs','--no-color','--tail','2000'),encoding='utf-8')
            except Exception: pass
            if not self.cleanup(): failure=(failure or '')+'; cleanup failed'
            result={'status':'FAIL' if failure else 'PASS','failure':failure,'steps':steps,'scope':'bounded local synthetic cross-service exporter/outage smoke; no performance or production claim'}
            self.write('trace-result.json',result)
            self.write('SHA256.json',{str(path.relative_to(self.evidence)):base.sha(path) for path in self.evidence.rglob('*') if path.is_file() and path.name!='SHA256.json'})
        print(json.dumps(result),flush=True)
        return int(bool(failure))

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--candidate',type=Path,required=True)
    parser.add_argument('--evidence',type=Path,required=True)
    args=parser.parse_args(); args.profile='load-only'; args.scenario='original'; args.ledger_cpus=1.; args.postgres_cpus=1.; args.kafka_cpus=.5; args.ledger_pool_size=12
    return TraceLab(args).run_trace()

if __name__=='__main__': raise SystemExit(main())
