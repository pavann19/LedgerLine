# Transfer query optimization

**The two application changes are implemented and regression-tested. Repeated Azure performance validation failed and neither planned sequence completed; no stable latency or throughput gain is claimed.** The earlier 2026-10-25 cloud results remain a separate historical measurement.

## Application changes

The fresh transfer path for the three safe strategies removes four PostgreSQL statements while preserving the financial and API contracts:

- Insert both postings in one `INSERT ... VALUES (...), (...) RETURNING ...` statement, replacing two inserts and the response read-back (two fewer statements).
- Validate status and currency using account records already returned under ordered `FOR SHARE` locks, replacing two account re-reads (two fewer statements).

Returned postings are sorted by generated ID to match replay order. IDs and timestamp precision come from PostgreSQL. Ownership checks, idempotency locking/rechecks, balance locks, transaction boundaries, outbox, audit and database constraints remain active. Replays still read existing postings. Application source is local commit `c53b8a2`; baseline `2c57cb3` has the same application as public `02ae501`.

## Correctness and security validation

Linux Java 21 `clean verify -Pcoverage` passed: **59 tests, zero failures, errors or skips**. Five image-policy negative-control checks also passed. Both exact application images passed the Trivy 0.74.0 gate with **zero fixable HIGH/CRITICAL findings and zero exceptions**.

New real-PostgreSQL tests exercise all three safe transaction strategies, exact amounts above floating-point integer precision, stored posting IDs/order/timestamps, identical replay posting responses, and rollback of both postings plus their transaction header when the credit violates a currency constraint. Existing account-closure, overflow, idempotency, concurrent-funds, outbox and projection regressions also passed. Each cloud deployment also passed its initial 22 HTTP checks and baseline SQL assertions.

## Azure observations: limits retained

Each experiment planned A-B-B-A-A-B, with fresh JVMs and a separate clone of an identical seed database for every case within that experiment. Both retained the original 150-second performance workload (target peak 100 virtual users), p95 <750 ms, p99 <1,500 ms, HTTP failure rate <1%, and the 180-second post-load projection/outbox deadline. Transfer amounts and directions are randomized by the unchanged workload.

| Experiment / case | Transfers | Transfers/s | p95 ms | p99 ms | Latency gate | Catch-up | Full SQL checks |
|---|---:|---:|---:|---:|---|---|---|
| P4 01-baseline | 31,204 | 208.0 | 538.2 | 943.2 | PASS | 107.7 s | PASS |
| P4 02-optimized | 34,597 | 230.6 | 487.0 | 816.1 | PASS | 131.0 s | PASS |
| P4 03-optimized | 32,947 | 219.6 | 510.9 | 872.4 | PASS | 118.4 s | PASS |
| P4 04-baseline | 22,076 | 147.1 | 848.1 | 1421.3 | FAIL | >180 s (FAIL) | Not reached |
| P15 01-baseline | 10,709 | 71.4 | 1794.9 | 3022.7 | FAIL | 100.1 s | PASS |
| P15 02-optimized | 12,173 | 81.2 | 1540.6 | 2574.7 | FAIL | >180 s (FAIL) | Not reached |

All **143,706 synthetic load transfers had zero HTTP failures**. Four cases completed the full monetary and full-history statement/daily-summary SQL checks. In the other two, the catch-up deadline failed before those SQL checks were reached; their final financial state is unverified. Neither six-case experiment completed, so results are not pooled into medians or a resume percentage claim.

The first P4/120-IOPS experiment stopped during its later baseline case. Azure metrics recorded 100% disk-IOPS consumption during multiple earlier intervals, establishing storage pressure without proving the complete cause of the later failure. The failed control is retained rather than excluded.

The repeat reused the **exact same application image bytes**, VM/CPU/memory allocations and workload. Both variants used P15/1,100-IOPS storage with the same 32 GiB capacity. Each verified scratch database was removed after its receipt was saved to avoid leaving completed databases' background maintenance in later cases. The original deployment database and immutable seed were retained until resource-group deletion. [Azure documents independent Premium SSD performance tiers](https://learn.microsoft.com/en-us/azure/postgresql/flexible-server/concepts-storage-premium-ssd).

The repeat was slower and also failed its latency gates. Its optimized case timed out with **24,354 ledger postings, 18,268 statement rows and zero unpublished outbox records**: a 6,086-row projection backlog remained at the observation point. This identifies unfinished downstream projection work, not a proven monetary inconsistency or a diagnosed root cause of the slower environment. Further profiling was not performed after the user chose to finish with measured limits.

These are bounded two-account contention tests with a generator on the application VM. The experiments used different storage settings and different seed datasets; their absolute results are not a code-only before/after comparison. Production capacity, stable percentage gains and completion of the new performance gates remain **unverified**. Existing resume/CV performance figures were not replaced by these failed experiments.

## Evidence and delivery

The [sanitized machine-readable report](evidence/transfer-query-optimization-2026-10-26.json) includes source/image identifiers, workload/probe hashes (raw uploaded bytes and canonical LF source), per-case results, failed observations and validation scope. Raw summaries, logs, resource samples, SQL receipts and cleanup receipts are retained privately. Both temporary resource groups are removed with confirmed absence before final delivery. Changes are local on `transfer-query-optimization`; no push or merge is part of this task.

A defensible implementation-only resume statement is: “Removed four PostgreSQL statements per fresh transfer through paired INSERT RETURNING and reuse of locked account metadata, preserving exact posting/replay semantics and passing 59 regression tests.” A new stable throughput/latency achievement is not supported by these experiments.

## Repeating the experiment

Build `ledgerline-transfer-baseline:2c57cb3` from baseline commit `2c57cb3` in a clean Linux checkout (the application is identical to public main `02ae501`), then run the following from the optimized checkout with Azure CLI signed in and Docker running:

```powershell
./scripts/azure-evidence-run.ps1 `
  -ComparisonBaselineLocalImage ledgerline-transfer-baseline:2c57cb3 `
  -RemoteOnly -RecoveryChecks `
  -VmSize Standard_D4ls_v6 `
  -PostgresSku Standard_D2ds_v5 -PostgresTier GeneralPurpose -PostgresPerformanceTier P15 `
  -LedgerMemory 2g -LedgerCpu 1.75 `
  -ProjectionMemory 1400m -ProjectionCpu 0.75 `
  -K6Profile performance -BudgetAmount 9500 `
  -EvidenceDirectory C:/private/ledgerline-comparison
```

This provisions paid temporary Azure resources and deletes its own resource group in `finally`, waiting for confirmed absence. The budget parameter creates a tracking budget in the account's billing currency; it is not a spending cap. In comparison mode the six performance cases replace the separate outage/duplicate/restore drills; the historical recovery measurements remain documented separately. The baseline image is checked locally before provisioning. The run records actual image IDs rather than trusting mutable tags.

The 2026-10-26 measurement used private orchestration copies that pin the previously built application images. The common runner exposes the same comparison and storage-performance options for repeat runs. The repository comparison probes and unchanged workload were used; probe revisions and the cleanup-method change are recorded separately. Local regression receipts, raw per-case load summaries/logs, resource samples, SQL checks and deletion receipts are retained privately.
