# Read-only reconciliation proposals

The worker reads only the two fixed aggregate HTTP tools and writes its own SQLite
job store. It has no SQL, shell, replay, offset acknowledgement or financial write
tool. Network destinations and schemas are fixed to the declared isolated topology;
tokens are supplied through RECONCILIATION_TOKEN and never persisted in job results.
Endpoint transport is HTTP for a local isolated lab, not a production TLS deployment.

```sh
python3 -B -m unittest discover -s reconciliation-agent -v
python3 -B reconciliation-agent/worker.py --help
docker build -t ledgerline-reconciliation:local reconciliation-agent
```

Run with --database <new-store> --source-version <reviewed-sha256> --once and an
explicit --enqueue-key to create a bounded synthetic job. The caller provides the
source digest; this is an identity contract, not cryptographic attestation. No model
means labelled offline-v1 analysis. --model selects an already installed local Ollama
model by name; weights are not digest-pinned. No automatic download or cloud fallback
exists. Missing model yields blocked. Model quality is unverified by offline tests.

Aggregate schema/role setup and migrations are described in
../docs/RECOVERY_AND_RECONCILIATION.md. Unknown lag blocks the result; deterministic
discrepancies cannot be downgraded by model output. Action IDs describe proposals
for operator review and are never executed. Retrying aggregate reads has no business
effect; stale leases fence result writes and inference retry does not move money.

Use one worker with one local SQLite WAL database and FULL synchronization. Queue,
daily, pending-job and disk ceilings reject new work rather than silently dropping
history. Back up using SQLite's online backup API or quiesce and retain all WAL state;
restore into a new path, verify integrity and deduplication before use. No automated
retention, distributed worker topology, financial repair or RTO/RPO is supplied.
On rollback retain original stores and source identity; do not bypass stale-job checks.
Requests have size/time limits; network timing is not a real-time scheduling guarantee.
