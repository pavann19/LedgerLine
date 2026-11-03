# Local performance investigation - 2026-11-03

**Bounded local experiment: PASS under the predeclared criteria.** Both candidate
runs pass the original latency/error/convergence gates. Candidate median throughput
is 15.56% higher and median p95 is 20.20% lower than the two baseline runs. The
checkout/query reduction is directly observed; shared-host variability prevents
assigning that complete percentage to code alone. Statistical significance,
production capacity and a 24-hour soak remain unverified.

The [public evidence JSON](evidence/performance-investigation-2026-11-03.json)
contains the four run summaries, source/artifact/image/workload fingerprints,
query fingerprints, profiling/resource observations, reconciliation and receipt
hashes. Raw logs, SQL, JFR recordings, host identities and synthetic credentials
remain excluded from the public artifact.

## Reproduction protocol

The predeclared [matched experiment protocol](PERFORMANCE_EXPERIMENT_2026-11-03.md)
is implemented by [benchmark-lab.py](../scripts/benchmark-lab.py), with source and
artifact identities frozen by [benchmark-manifest.py](../scripts/benchmark-manifest.py).
The protocol preserves the original performance and financial-correctness gates.

Use isolated synthetic fixtures, fresh service volumes and an identified source
build. Retain the generated effective configuration, source/image identities,
workload hashes, metrics, query statistics, profiling status, reconciliation,
cleanup receipts and checksums for every attempt. Never reuse existing financial
data or shared service volumes. Use a new evidence directory for each invocation.

The measured workload remains the original 150-second, two-account transfer
profile, with unchanged p95 <750 ms, p99 <1,500 ms and unexpected error fraction
<0.01 gates. A separate many-account profile is diagnostic; it cannot replace the
hot-account acceptance profile. Record timed successful transfers separately
from fixture/setup traffic.

Declared fixture limits: ledger 2 CPUs / 768 MiB, PostgreSQL 2 CPUs / 600 MiB,
Kafka 1 CPU / 700 MiB, projection 0.75 CPUs / 640 MiB, ledger connection pool 4.
These are container caps on a shared, oversubscribed host, not dedicated capacity.
Keep source instrumentation, configuration, warm-up and workload consistent
between baseline and candidate runs. The load generator is capped at 0.5 CPU /
256 MiB. Original workload randomness is retained, so request traces and fixture
UUIDs differ. Do not change acceptance thresholds. The predeclared improvement
criterion requires both candidate runs to pass the original gates, plus at least
10% higher median throughput and 10% lower median p95 than the two baseline runs.

## Baseline B1: baseline-1b

| Measurement | Value |
|---|---:|
| Timed transfers | 19,037 |
| Throughput | 126.90 transfers/s |
| HTTP latency mean | 314.19 ms |
| HTTP latency p50 | 198.66 ms |
| HTTP latency p95 | 985.85 ms |
| HTTP latency p99 | 1,861.14 ms |
| HTTP failure fraction | 0 |
| Performance gate | **FAIL** |
| Profiling collection | PASS, using recovered validated JFR repository chunks |

Both latency gates fail. Zero HTTP failures does not make the performance result
pass. Interpret raw k6 threshold fields through the harness and numeric values;
their boolean representation must not override the explicit threshold comparison.

### Quantitative attribution

| Application measurement | Mean |
|---|---:|
| Connection acquisition, per acquisition | 93.28 ms |
| Connection acquisition sum / timed HTTP requests | 280.82 ms/request |
| Transaction start | 16.81 ms |
| Idempotency key lock | 1.08 ms |
| Replay check within transaction | 0.82 ms |
| Metadata locks and validation | 1.91 ms |
| Balance lock acquisition | 10.93 ms |
| Balance acquisition through transaction completion | 7.37 ms |
| Persistence | 3.41 ms |
| Commit and cleanup | 3.87 ms |
| Total transaction | 38.98 ms |

There were 57,309 connection acquisitions for 19,037 timed HTTP requests
(3.0104 acquisitions/request). Background work contributes to this aggregate.
The application performs an authorization lookup, a speculative replay lookup
and the authoritative transaction. The summed acquisition time corresponds to
280.82 ms/request, while transaction start averages 16.81 ms. This supports pool
queueing before the transaction as a major observed source of HTTP delay. The
rough difference of 264 ms/request is an aggregate estimate, not a directly
traced exclusive span or a promised optimization saving.

PostgreSQL recorded 38,074 idempotency lookups returning zero rows: the initial
probe and the authoritative replay check each ran for every new-key transfer.
It also recorded 38,074 individual metadata share-lock queries, averaging
0.064 ms each on the server. Balance-lock SQL averaged 9.51 ms compared with
10.93 ms for its application phase. The combined write SQL averaged approximately
0.875 ms compared with 3.41 ms for application persistence. Deferred invariant
aggregates averaged 0.0335 ms each. Server query execution can include waits;
these measurements do not establish CPU consumption or justify removing checks.

JFR contains 3,650 execution samples, 76,583 park events, 9,672 socket reads and
42 monitor-entry events for the ledger service. Generic park/socket top frames
do not identify their complete callers or directly measure an HTTP latency
distribution. No leading monitor-entry frame establishes transfer metric
contention. The normal recording dump failed with an I/O error; validated JFR
repository chunks were recovered and exported. Preserve the export receipt and
profiling status together with the recording.

## Candidate mechanism and comparison plan

The narrow candidate removes the speculative initial replay query from the
pessimistic strategy. It retains source authorization, the transaction-scoped
idempotency lock, replay/hash validation after that lock, deterministic financial
locks, checked arithmetic, durable commit and database invariants. The tested
mechanism is fewer unnecessary connection checkouts and round trips on new-key
requests. Completed replays now enter the transaction and acquire the key lock;
replay-heavy workload performance is unmeasured, and those requests can incur
additional pool and key-lock waiting. For valid identical key/body requests, the
locked replay check still returns the committed transaction without repeating
its financial writes.

Source authorization remains before strategy execution. Shape validation now
precedes the locked replay/hash check: a changed body that makes source and
destination identical can return HTTP 400 before the idempotency-conflict
response (HTTP 422 in the current API). Nonpositive amounts passed directly to
the service have the same exception-precedence change; HTTP bean validation
already rejects those amounts. This is an error-precedence compatibility change,
not evidence that replay-heavy performance or every malformed request combination
has been tested.

Falsifiable predictions: replay query count falls from approximately 2N to N;
connection acquisitions fall from approximately 3N to 2N, allowing for background
work. End-to-end latency and acquisition waiting per HTTP request must be compared
alongside throughput and balance-lock timing. If lower pool waiting is absorbed
by increased lock waiting, the queue moved; a capacity improvement is unproven.
Any metadata batching or broader server-side transfer implementation requires a
separate identified candidate and its own comparison.

| Run | Transfers | Transfers/s | p50 ms | p95 ms | p99 ms | Error fraction | Gate | Profile / cleanup |
|---|---:|---:|---:|---:|---:|---:|---|---|
| B1 (baseline) | 19,037 | 126.90 | 198.66 | 985.85 | 1861.14 | 0 | FAIL | PASS / PASS |
| C1 (candidate) | 24,615 | 164.10 | 167.38 | 686.89 | 1117.87 | 0 | PASS | PASS / PASS |
| C2 (candidate) | 26,202 | 174.67 | 157.83 | 649.78 | 1103.60 | 0 | PASS | PASS / PASS |
| B2 (baseline) | 24,937 | 166.25 | 160.53 | 689.24 | 1152.23 | 0 | PASS | PASS / PASS |

The median throughput is **146.57 → 169.38 transfers/s (+15.5605%)**; median
p95 is **837.54 → 668.33 ms (−20.2033%)**. The declared ≥10% median
improvement requirements and both candidate original gates pass. All four legs
have complete required profiling and scoped cleanup with no remaining resources
or cleanup errors.

Paired changes differ: B1 → C1 throughput **+29.31%**, p95 **−30.33%**;
B2 → C2 throughput **+5.0647%**, p95 **−5.725%**. Baseline throughput itself
rose approximately **31%** between B1 and B2, and B2 passes the original gates.
The candidate is not faster than every baseline observation. Host/runtime/order
variation materially confounds the exact code-only effect. Two repeats and
randomized traffic establish neither statistical significance nor a universal gain.

## Observed mechanism and remaining queue

| Run | Acquisitions/request | Acquisition wait/request ms | Replay queries/request | Transaction start ms | Total transaction ms | Balance lock ms | Lock-to-completion ms |
|---|---:|---:|---:|---:|---:|---:|---:|
| B1 | 3.0104 | 280.82 | 2 | 16.81 | 38.98 | 10.93 | 7.37 |
| C1 | 2.0098 | 212.24 | 1 | 25.15 | 43.60 | 9.76 | 5.68 |
| C2 | 2.0106 | 199.25 | 1 | 21.40 | 39.08 | 9.67 | 5.29 |
| B2 | 3.0113 | 209.60 | 2 | 12.64 | 30.37 | 9.51 | 5.55 |

Exactly one replay lookup per new-key request is removed; connection acquisitions
fall from approximately three to two per request, including background work.
Metadata reads remain two per request. B1 → C1 acquisition waiting/request falls
24.42%, closely matching the 24.41% HTTP mean reduction. Individual acquisition
mean nevertheless rises 93.28 → 105.60 ms and transaction start rises
16.81 → 25.15 ms. The remaining queue is substantial and partly redistributed;
the change removes redundant demand rather than eliminating contention.

| Run | Balance-lock SQL mean ms | Journal SQL weighted mean ms | Metadata SQL mean ms | Replay SQL mean ms |
|---|---:|---:|---:|---:|
| B1 | 9.511 | 0.875 | 0.064 | 0.058 |
| C1 | 8.715 | 0.760 | 0.054 | 0.054 |
| C2 | 8.771 | 0.717 | 0.050 | 0.052 |
| B2 | 8.543 | 0.735 | 0.051 | 0.050 |

Server SQL timings include waits and are aggregate means, not per-query p95.
The balance-lock query remains a contention point. Application persistence and
commit timing include client, scheduling and cleanup work beyond server SQL.

## Resource, thread-pool and convergence observations

| Run | Ledger CPU / throttled seconds | DB CPU / throttled seconds | Kafka / projection throttled seconds | Ledger sampled cgroup memory max MiB | JVM used memory sampled max MiB |
|---|---|---|---|---:|---:|
| B1 | 164.98 / 0.530 | 98.32 / 0.000 | 13.84 / 8.48 | 413.52 | 223.73 |
| C1 | 168.36 / 0.416 | 105.95 / 0.000 | 23.22 / 7.45 | 435.73 | 212.10 |
| C2 | 161.40 / 0.545 | 112.93 / 0.000 | 22.37 / 9.81 | 428.74 | 220.24 |
| B2 | 163.44 / 0.657 | 113.09 / 0.000 | 22.84 / 8.47 | 434.64 | 224.00 |

CPU counters span profiling collection boundaries, including observer work;
their windows are not exact request CPU costs. PostgreSQL was not cgroup-throttled
in these samples; Kafka/projection throttling and shared-host competition remain.
All exported cgroup memory failure counters stayed zero. Sampled memory extrema
are not continuous peaks; this short run does not establish absence of leaks.

| Run | Hikari active / pending sampled max | Tomcat current / busy sampled max | JVM live threads sampled max | DB connections / blocked sampled max | Ledger JFR CPU samples / parks / socket reads |
|---|---|---|---:|---|---|
| B1 | 4 / 77 | 100 / 86 | 121 | 12 / 3 | 3650 / 76583 / 9672 |
| C1 | 4 / 80 | 100 / 84 | 121 | 11 / 3 | 3866 / 73682 / 10112 |
| C2 | 4 / 91 | 99 / 96 | 120 | 11 / 2 | 3315 / 76548 / 10709 |
| B2 | 4 / 84 | 98 / 87 | 119 | 11 / 3 | 3481 / 95064 / 9953 |

The pool reaches its configured four active connections and retains pending
requests in every leg. Thread and JFR observations are sampled; park/socket
event counts do not measure their share of HTTP latency.

| Run | Kafka lag sampled max / final profile / reconciled | Convergence seconds | Ledger = projection postings | Invariants |
|---|---|---:|---:|---|
| B1 | 24 / 24 / 0 | 1.249 | 38,114 | PASS |
| C1 | 33 / 0 / 0 | 2.100 | 49,270 | PASS |
| C2 | 46 / 26 / 0 | 1.200 | 52,444 | PASS |
| B2 | 50 / 18 / 0 | 1.172 | 49,914 | PASS |

All legs converged within the original 180-second bound, with zero monetary,
statement or summary drift, zero unbalanced transactions, zero protected negative
balances, no pending outbox or quarantine, and known final consumer lag zero.
Profiling lag is measured before settling and must not be confused with final
reconciliation. Posting totals include the synthetic funding/setup postings.

## Baseline reconstruction and remaining validation

The [reproduction guide](PERFORMANCE_BENCHMARK.md) supplies the exact
[`git apply` baseline patch](../bench/baselines/restore-speculative-replay.patch)
and benchmark commands. The public JSON records reconstruction **PASS: 93/93 production files
byte-identical and equal production file sets**, with the reconstruction patch
SHA-256. Later test improvements are explicitly listed; this does not claim
identical rebuilt JAR bytes. Keep the patch and source hashes with reproduction
instructions rather than inferring a baseline from a commit name alone.

The separate 60-second, twenty-account diagnostic passed: 5,843 transfers at 97.39/s, p50 69.36 ms, p95 310.28 ms, p99 497.31 ms and zero HTTP failures. Its workload differs from the original 150-second, 100-VU profile, so their throughput cannot be compared. Profiling, convergence and cleanup passed.

The real [cross-service tracing smoke](TRACE_FLOW_SMOKE.md) passed exported HTTP/outbox/projection parent relationships, persisted Kafka context, stopped-collector commit/replay/convergence and cleanup. Both services emitted ECS JSON logs; six observed records carried trace and span IDs. The candidate passed 121 Java tests, nine worker tests and zero-defect static analysis with a rejecting known-defect control. See the public JSON for these separate receipts.

Hosted CI must pass for the exact published revision: [feature-branch runs](https://github.com/pavann19/LedgerLine/actions/workflows/ci.yml?query=branch%3Abackend-hardening-20261103). Local verification does not certify a hosted run. The bounded four-leg experiment passes; broader release and endurance acceptance do not follow from it.

## Retained exploratory failures and limitations

The earlier a-e attempts are preserved in the
[earlier retained summary](evidence/backend-hardening-2026-11-03.json).
All failed the original p95 gate. They changed code and/or resource configuration
sequentially and cannot establish an isolated code-only improvement.

The first round-two exploratory baseline, baseline-1, measured 118.14 transfers/s,
p50 220.43 ms, p95 1,023.07 ms and p99 2,017.53 ms with zero HTTP failures.
Its JFR export failed, so it cannot satisfy the complete profiling requirement.
It remains retained rather than replaced by baseline-1b.

Timing means use count/sum deltas around the workload. Aggregate phase timers
overlap; percentile sums are invalid. Transaction totals exclude the baseline's initial replay,
authorization and HTTP processing. Commit-cleanup includes transaction completion
and resource release rather than pure WAL flush. Balance-lock timing includes
JDBC/server wait, execution and mapping. Metric recording adds overhead, including
inside lock ownership, and must remain equivalent across compared sources.
Rolling timer maxima are not exact workload maxima.

The bounded local comparison and final financial convergence are **PASS** under
the declared protocol. Recovery/restore, broader release acceptance and publication
status require their own evidence. No required 24-hour soak, continuous production
operation, hosted CI status or production readiness is established here.
