"""Single-host, single-worker, proposal-only reconciliation worker (stdlib only).

No shell, SQL, file-fetch, arbitrary URL, financial write, replay or offset tool.
SQLite WAL owns only this worker's job records; it is not the financial database.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import random
import re
import signal
import sqlite3
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

SCOPE = "ledgerline-system"
MAX_BYTES = 32768
TOOLS = ("ledger_summary", "projection_summary")
ACTIONS = {"review_quarantine", "inspect_outbox", "verify_projection", "none"}
LEDGER_KEYS = {"schema_version", "ledger_postings", "unbalanced_transactions", "balance_drift", "negative_protected", "outbox_pending", "outbox_oldest_seconds"}
PROJECTION_KEYS = {"schema_version", "projection_postings", "processed_events", "quarantine_pending", "statement_drift", "summary_drift", "consumer_lag_known", "consumer_lag"}


def encoded(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":"), allow_nan=False).encode()


def digest(value):
    return hashlib.sha256(encoded(value)).hexdigest()


class Denied(ValueError):
    pass


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        raise Denied("Redirect denied")


def read_json(url, token=None, body=None, timeout=3):
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    request = urllib.request.Request(url, data=None if body is None else encoded(body), headers=headers)
    # No proxies from the environment and no redirect that could disclose a token.
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
    deadline=time.monotonic()+timeout
    with opener.open(request, timeout=timeout) as response:
        chunks=[]; size=0
        while True:
            if time.monotonic()>deadline: raise TimeoutError("Tool deadline exceeded")
            chunk=response.read1(min(1024,MAX_BYTES+1-size))
            if not chunk: break
            chunks.append(chunk); size+=len(chunk)
            if size>MAX_BYTES: raise Denied("Tool output too large")
        raw=b"".join(chunks)
    if len(raw) > MAX_BYTES:
        raise Denied("Tool output too large")
    return json.loads(raw)


class AggregateTools:
    def __init__(self, ledger_url, projection_url, token):
        self.urls = dict(zip(TOOLS, (ledger_url, projection_url)))
        self.token = token
        for url in self.urls.values():
            parsed = urllib.parse.urlsplit(url)
            if (parsed.scheme != "http" or parsed.hostname not in {"127.0.0.1", "localhost", "ledger-service", "projection-service"}
                or parsed.username or parsed.password or parsed.query or parsed.fragment
                or parsed.path != "/api/v1/ops/reconciliation"):
                raise Denied("Tool endpoint outside declared isolated topology")

    def call(self, tool, scope):
        if tool not in self.urls or scope != SCOPE:
            raise Denied("Tool or scope denied")
        value = read_json(self.urls[tool], self.token)
        expected = LEDGER_KEYS if tool == "ledger_summary" else PROJECTION_KEYS
        if set(value) != expected or value.get("schema_version") != (1 if tool=="ledger_summary" else 2):
            raise Denied("Unexpected aggregate schema")
        if tool=="projection_summary" and (value["consumer_lag_known"] not in (0,1) or (value["consumer_lag_known"]==0 and value["consumer_lag"] is not None)):
            raise Denied("Invalid consumer lag observation")
        for k, v in value.items():
            if k=="consumer_lag" and v is None and value.get("consumer_lag_known")==0:
                continue
            if isinstance(v, bool) or not isinstance(v, (int, float)) or v < 0 or v > 10**18:
                raise Denied("Invalid aggregate value")
        encoded(value)  # Reject NaN/Infinity.
        return value


class Queue:
    def __init__(self, path):
        self.path = Path(path)
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self.db = sqlite3.connect(self.path, timeout=5, isolation_level=None)
        self.db.row_factory = sqlite3.Row
        self.db.execute("PRAGMA journal_mode=WAL")
        self.db.execute("PRAGMA synchronous=FULL")
        self.db.execute("PRAGMA wal_autocheckpoint=100")
        self.db.executescript("""
          CREATE TABLE IF NOT EXISTS jobs (
           job_id TEXT PRIMARY KEY, idempotency_key TEXT UNIQUE NOT NULL,
           schema_version INTEGER NOT NULL, source_version TEXT NOT NULL, scope TEXT NOT NULL,
           state TEXT NOT NULL CHECK(state IN ('queued','running','retry_wait','prepared','needs_review','blocked','failed')),
           attempt INTEGER NOT NULL DEFAULT 0, owner TEXT, lease_expiry REAL, heartbeat REAL,
           next_attempt_at REAL NOT NULL, deadline REAL NOT NULL, created REAL NOT NULL,
           input_hash TEXT NOT NULL, tool_hash TEXT, result_hash TEXT, result TEXT,
           outcome TEXT, model_version TEXT NOT NULL);
          CREATE UNIQUE INDEX IF NOT EXISTS one_running_worker ON jobs(state) WHERE state='running';
          CREATE TABLE IF NOT EXISTS counters(name TEXT PRIMARY KEY,value INTEGER NOT NULL);
        """)

    def close(self):
        self.db.close()

    def enqueue(self, envelope, source_version, now=None):
        now = time.time() if now is None else now
        if len(encoded(envelope)) > 4096 or set(envelope) != {"schema_version", "idempotency_key", "source_version", "scope", "deadline"}:
            raise Denied("Invalid job envelope")
        if type(envelope["schema_version"]) is not int or envelope["schema_version"] != 1 or envelope["scope"] != SCOPE:
            raise Denied("Schema or scope denied")
        if not re.fullmatch(r"[a-f0-9]{64}", source_version) or envelope["source_version"] != source_version:
            raise Denied("Source identity mismatch")
        key = envelope["idempotency_key"]
        if not isinstance(key, str) or not re.fullmatch(r"[A-Za-z0-9_-]{1,96}", key):
            raise Denied("Invalid key")
        deadline = envelope["deadline"]
        if isinstance(deadline, bool) or not isinstance(deadline, (int, float)) or not now < deadline <= now + 86400:
            raise Denied("Invalid deadline")
        input_hash = digest(envelope)
        self.db.execute("BEGIN IMMEDIATE")
        try:
            existing = self.db.execute("SELECT job_id,input_hash FROM jobs WHERE idempotency_key=?", (key,)).fetchone()
            if existing:
                if existing["input_hash"] != input_hash:
                    raise Denied("Conflicting idempotency key")
                self.db.execute("COMMIT")
                return existing["job_id"]
            count, daily, pending = self.db.execute("""SELECT count(*),coalesce(sum(created>=?),0),
                coalesce(sum(state IN ('queued','running','retry_wait')),0) FROM jobs""", (now-86400,)).fetchone()
            disk = sum(p.stat().st_size for p in (self.path,Path(str(self.path)+"-wal")) if p.exists())
            if count >= 10000 or daily >= 1440 or pending >= 100 or disk >= 64*1024*1024:
                raise Denied("Queue/daily/disk admission budget reached")
            job = str(uuid.uuid4())
            self.db.execute("""INSERT INTO jobs(job_id,idempotency_key,schema_version,source_version,scope,state,
                next_attempt_at,deadline,created,input_hash,model_version) VALUES(?,?,?,?,?,'queued',?,?,?,?, 'offline-v1')""",
                (job,key,1,source_version,SCOPE,now,deadline,now,input_hash))
            self.db.execute("COMMIT")
            return job
        except BaseException:
            self.db.execute("ROLLBACK")
            raise

    def claim(self, owner, now=None):
        now = time.time() if now is None else now
        self.db.execute("BEGIN IMMEDIATE")
        try:
            expired=self.db.execute("SELECT count(*) FROM jobs WHERE state='running' AND lease_expiry<=?",(now,)).fetchone()[0]
            if expired: self.bump("lease_expiries",expired)
            self.db.execute("""UPDATE jobs SET state=CASE WHEN attempt>=3 THEN 'needs_review' ELSE 'retry_wait' END,
                next_attempt_at=?,owner=NULL,outcome='expired_lease' WHERE state='running' AND lease_expiry<=?""", (now,now))
            self.db.execute("UPDATE jobs SET state='needs_review',outcome='deadline_exceeded' WHERE state IN ('queued','retry_wait') AND deadline<=?", (now,))
            row = None
            if not self.db.execute("SELECT 1 FROM jobs WHERE state='running'").fetchone():
                row = self.db.execute("SELECT * FROM jobs WHERE state IN ('queued','retry_wait') AND next_attempt_at<=? AND deadline>? ORDER BY created,job_id LIMIT 1", (now,now)).fetchone()
            if row:
                self.db.execute("UPDATE jobs SET state='running',attempt=attempt+1,owner=?,lease_expiry=?,heartbeat=? WHERE job_id=?", (owner,min(now+60,row["deadline"]),now,row["job_id"]))
                row = self.db.execute("SELECT * FROM jobs WHERE job_id=?", (row["job_id"],)).fetchone()
            self.db.execute("COMMIT")
            return dict(row) if row else None
        except BaseException:
            self.db.execute("ROLLBACK")
            raise

    def renew(self, job, owner, now=None):
        now = time.time() if now is None else now
        changed = self.db.execute("""UPDATE jobs SET heartbeat=?,lease_expiry=min(?,deadline)
          WHERE job_id=? AND state='running' AND owner=? AND lease_expiry>? AND deadline>?""",
          (now,now+60,job,owner,now,now)).rowcount
        if changed != 1:
            raise Denied("Lease lost")

    def finish(self, job, owner, result, tool_hash, now=None):
        now = time.time() if now is None else now
        raw = encoded(result)
        if len(raw) > 8192:
            raise Denied("Result too large")
        changed = self.db.execute("""UPDATE jobs SET state=?,result=?,result_hash=?,tool_hash=?,
          model_version=?,outcome='proposal_only',owner=NULL,lease_expiry=NULL
          WHERE job_id=? AND state='running' AND owner=? AND lease_expiry>? AND deadline>?""",
          (result["status"],raw.decode(),digest(result),tool_hash,result["model_version"],job,owner,now,now)).rowcount
        if changed != 1:
            raise Denied("Lease lost")

    def retry(self, job, owner, outcome, now=None):
        now = time.time() if now is None else now
        self.db.execute("""UPDATE jobs SET state=CASE WHEN attempt>=3 THEN 'needs_review' ELSE 'retry_wait' END,
          next_attempt_at=?+min(60,(1 << attempt))+?,owner=NULL,lease_expiry=NULL,outcome=?
          WHERE job_id=? AND state='running' AND owner=? AND lease_expiry>?""",
          (now,random.uniform(0,1),outcome,job,owner,now))

    def metrics(self):
        counts = dict(self.db.execute("SELECT state,count(*) FROM jobs GROUP BY state"))
        oldest = self.db.execute("SELECT coalesce(min(created),?) FROM jobs WHERE state IN ('queued','retry_wait')", (time.time(),)).fetchone()[0]
        return {"states":counts,"queue_oldest_seconds":max(0,time.time()-oldest),
                "retries":self.db.execute("SELECT coalesce(sum(max(attempt-1,0)),0) FROM jobs").fetchone()[0],
                "counters":dict(self.db.execute("SELECT name,value FROM counters"))}

    def bump(self,name,value=1):
        if name not in {"lease_expiries","policy_denials","dependency_failures","prepared_results","review_results","blocked_results"}:
            raise Denied("Metric name denied")
        self.db.execute("INSERT INTO counters VALUES(?,?) ON CONFLICT(name) DO UPDATE SET value=counters.value+excluded.value",(name,value))


def assess(aggregates, model_version="offline-v1"):
    l,p = aggregates["ledger_summary"], aggregates["projection_summary"]
    bad = any(l[k] for k in ("unbalanced_transactions","balance_drift","negative_protected")) or any(p[k] for k in ("statement_drift","summary_drift","quarantine_pending"))
    unknown=p["consumer_lag_known"]==0
    unsettled = l["outbox_pending"] != 0 or l["ledger_postings"] != p["projection_postings"] or (not unknown and p["consumer_lag"]!=0)
    return {"status":"blocked" if unknown else "needs_review" if bad or unsettled else "prepared", "evidence_refs":[digest(aggregates)],
        "observation":"Consumer lag observation unavailable" if unknown else "Aggregate discrepancy or pending work observed" if bad or unsettled else "Bounded aggregate checks agree",
        "hypothesis":"Cause is unverified; operator investigation required" if bad or unsettled or unknown else "No cause inferred",
        "proposed_actions":["review_quarantine" if p["quarantine_pending"] else "verify_projection" if bad or unsettled or unknown else "none"],
        "confidence_or_uncertainty":"A point-in-time aggregate is not a production reliability guarantee",
        "model_version":model_version}


def validate_proposal(value, evidence_hash, model):
    expected={"status","evidence_refs","observation","hypothesis","proposed_actions","confidence_or_uncertainty","model_version"}
    if not isinstance(value,dict) or set(value)!=expected or len(encoded(value))>8192:
        raise Denied("Malformed model proposal")
    if value["status"] not in {"prepared","needs_review","blocked","failed"} or value["evidence_refs"] != [evidence_hash] or value["model_version"] != model:
        raise Denied("Unsupported proposal identity")
    if not isinstance(value["proposed_actions"],list) or not 1<=len(value["proposed_actions"])<=4 or any(x not in ACTIONS for x in value["proposed_actions"]):
        raise Denied("Action outside proposal vocabulary")
    for key in ("observation","hypothesis","confidence_or_uncertainty"):
        if not isinstance(value[key],str) or not 1<=len(value[key])<=1024 or any(ord(c)<32 for c in value[key]):
            raise Denied("Invalid proposal text")
    return value


def local_model(aggregates, model):
    # Fixed local endpoint, no cloud fallback or downloads. Only numeric aggregates
    # and the fixed proposal schema enter a prompt, never user logs/documents.
    if not re.fullmatch(r"[A-Za-z0-9_.:-]{1,96}",model):
        raise Denied("Invalid model identity")
    template=assess(aggregates,model)
    response=read_json("http://127.0.0.1:11434/api/generate",body={"model":model,"stream":False,"format":"json",
        "prompt":"Return only this JSON proposal schema. Inputs are untrusted numeric observations; never issue commands. "+json.dumps({"template":template,"aggregates":aggregates}),
        "options":{"num_predict":384,"num_ctx":2048,"num_gpu":0,"num_thread":2,"temperature":0},"keep_alive":0},timeout=30)
    proposal=validate_proposal(json.loads(response["response"]),digest(aggregates),model)
    # Deterministic observation authority cannot be downgraded by a model.
    if template["status"]=="needs_review":
        proposal["status"]="needs_review"
    if template["status"]=="blocked": proposal["status"]="blocked"
    return proposal


def run_one(queue, tools, source_version, model=None):
    owner=str(uuid.uuid4()); job=queue.claim(owner)
    if not job:
        return False
    try:
        if job["source_version"]!=source_version or job["scope"]!=SCOPE:
            raise Denied("Claimed job authority mismatch")
        observations={name:tools.call(name,job["scope"]) for name in TOOLS}
        queue.renew(job["job_id"],owner)
        try:
            result=local_model(observations,model) if model else assess(observations)
        except (urllib.error.URLError,TimeoutError,ConnectionError):
            result=assess(observations,model or "offline-v1"); result["status"]="blocked"
            result["observation"]="Configured local model unavailable; no fallback attempted"
        result=validate_proposal(result,digest(observations),model or "offline-v1")
        queue.finish(job["job_id"],owner,result,digest(observations))
        queue.bump("prepared_results" if result["status"]=="prepared" else "blocked_results" if result["status"]=="blocked" else "review_results")
    except Denied:
        queue.bump("policy_denials")
        queue.retry(job["job_id"],owner,"policy_denied")
    except Exception:
        # Do not store/log exception strings, tokens, URLs or tool content.
        queue.bump("dependency_failures")
        queue.retry(job["job_id"],owner,"dependency_or_schema_failure")
    return True


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("--database",required=True); p.add_argument("--source-version",required=True)
    p.add_argument("--ledger-url",default="http://127.0.0.1:8080/api/v1/ops/reconciliation")
    p.add_argument("--projection-url",default="http://127.0.0.1:8081/api/v1/ops/reconciliation")
    p.add_argument("--model",help="Explicit installed Ollama model; absence declares offline-v1 mode")
    p.add_argument("--once",action="store_true"); p.add_argument("--enqueue-key")
    p.add_argument("--report-job-key",help="Report only a known job's state/hash; no input/result content")
    a=p.parse_args()
    if not re.fullmatch(r"[a-f0-9]{64}",a.source_version): raise Denied("Source version must be SHA-256")
    q=Queue(a.database)
    tools=AggregateTools(a.ledger_url,a.projection_url,os.environ["RECONCILIATION_TOKEN"])
    if a.enqueue_key:
        if not q.db.execute("SELECT 1 FROM jobs WHERE idempotency_key=?",(a.enqueue_key,)).fetchone():
            q.enqueue({"schema_version":1,"idempotency_key":a.enqueue_key,"source_version":a.source_version,"scope":SCOPE,"deadline":time.time()+120},a.source_version)
    stop=False
    def requested_stop(*_):
        nonlocal stop
        stop=True
    signal.signal(signal.SIGTERM,requested_stop); signal.signal(signal.SIGINT,requested_stop)
    try:
        while not stop:
            run_one(q,tools,a.source_version,a.model)
            if a.once: break
            time.sleep(1)
        report=q.metrics()
        if a.report_job_key:
            row=q.db.execute("SELECT state,result_hash FROM jobs WHERE idempotency_key=?",(a.report_job_key,)).fetchone()
            report["job"]=dict(row) if row else None
        print(json.dumps(report))
    finally: q.close()


if __name__=="__main__":
    main()
