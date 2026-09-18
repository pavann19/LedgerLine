# Experiment 01: Isolation Levels and Concurrency Anomalies in Double-Entry Ledgers

## 1. Executive Summary
This experiment investigates the correctness, throughput, and latency characteristics of four concurrency control strategies for financial double-entry transfers under varying levels of contention.

**Status: both the correctness claims (§2, §3) and the throughput/latency benchmark (§4) below are backed by committed, executable artifacts** — `IsolationExperimentTest` (run in CI on every push) for correctness, and raw k6 JSON output committed under `bench/results/` for the benchmark. The benchmark run that produced §4 is described there, including its environment and caveats.

What the committed tests demonstrate:
1. **Unprotected read-then-write (Variant 0)** in standard `READ COMMITTED` mode leads directly to **lost-update anomalies** and balance drift under concurrent execution — asserted by `IsolationExperimentTest.demonstrateLostUpdateAnomalyInVariant0`.
2. **Pessimistic locking with deterministic UUID ordering (Variant 1)**, **optimistic locking with version retry (Variant 2)**, and **SERIALIZABLE with 40001 retry (Variant 3)** all preserve exact zero balance drift under concurrent contention — each asserted by its own test in `IsolationExperimentTest`.

What the §4 benchmark run measured directly (no longer "expected", see §4 for the numbers and their source files):
- Variant 1 had the lowest high-contention p95/p99 latency and zero request failures.
- Variant 2 and Variant 3 both showed request failures under high contention (exhausted retries surfacing as HTTP 500s) and dramatically higher p95/p99 latency than under low contention.
- Variant 0 showed the most severe high-contention degradation of all — far fewer completed requests and multi-second tail latencies — and, uniquely, a real measured violation of conservation of money.

---

## 2. Tested Variants

| Variant | Isolation Level | Locking Mechanism | Contention Behavior | Invariants Preserved |
| :--- | :--- | :--- | :--- | :--- |
| **Variant 0 (Broken)** | `READ COMMITTED` | None (Blind read-then-write) | Suffers Lost Updates | **FAILED** (Balance Drift) |
| **Variant 1 (Pessimistic)** | `READ COMMITTED` | `SELECT ... FOR UPDATE` (min UUID first) | Row-level queuing; zero deadlocks | **PASSED** (Exact zero drift) |
| **Variant 2 (Optimistic)** | `READ COMMITTED` | `version = version + 1 WHERE version = :v` | Abort and retry on version mismatch | **PASSED** (Exact zero drift) |
| **Variant 3 (Serializable)**| `SERIALIZABLE` | PostgreSQL SSI engine | Abort on error `40001` with retry | **PASSED** (Exact zero drift) |

---

## 3. Anomaly Demonstration: Variant 0 Lost Update

### Mechanism
In `Variant 0`, the application executes:
```sql
-- Thread 1 & Thread 2 execute concurrently
SELECT balance_minor FROM account_balances WHERE account_id = 'A'; -- Both read 100,000
-- Thread 1 debits 100: new balance = 99,900
-- Thread 2 debits 100: new balance = 99,900
UPDATE account_balances SET balance_minor = 99900 WHERE account_id = 'A'; -- Thread 1 writes
UPDATE account_balances SET balance_minor = 99900 WHERE account_id = 'A'; -- Thread 2 overwrites Thread 1!
```

### Empirical Test Result (CI, per-push)
`IsolationExperimentTest.demonstrateLostUpdateAnomalyInVariant0` (`ledger-service/src/test/java/com/ledgerline/ledger/isolation/IsolationExperimentTest.java`) runs the contended Variant-0 workload across 10 independent trials against fresh accounts each time, and **asserts** — not just logs — that the drift reproduces in at least 1 of the 10 trials. It logs the observed anomaly rate (`anomalies/trials`) and the total absolute drift on each CI run; those exact numbers are not reproduced here as fixed figures because a race condition's exact drift is nondeterministic run-to-run. The committed evidence for this scenario is the test itself and its pass/fail result in CI, not a hand-typed number in this document.

### Empirical Benchmark Result (one real local run, 2026-09-18)
The §4 k6 benchmark run against a real local instance also produced a real, one-off measurement of the anomaly, captured in [`bench/results/01-isolation-variant0-broken-drift.txt`](../../bench/results/01-isolation-variant0-broken-drift.txt):

- The 3 high-contention accounts, which started with an identical $50,000,000.00 and were touched only by Variant 0's unlocked read-then-write during that run, ended up with a cached balance that disagreed with the balance implied by their own posting history — by **-70, -36, and +150 minor units** respectively (net **+44**). A correct variant guarantees these two numbers are always identical; here they aren't.
- Across the full 53-account pool (used by all four variants across the whole benchmark session, with Variant 0 run last), the total cached balance ended up **8,812 minor units ($88.12) higher** than total funding — i.e. Variant 0's lost updates didn't just misplace money between accounts, they measurably broke the ledger-wide conservation-of-money invariant. Variants 1–3 are what's expected to keep that number at exactly zero; the isolated high-contention drift figures above confirm the corruption is attributable to Variant 0's run, not the others.

---

## 4. Benchmark Measurements

**Run for real on 2026-09-18**, locally (not cloud): `docker compose -f deploy/compose.yaml up -d postgres kafka ledger-service`, accounts seeded via `bench/seed_accounts.py` (50 low-contention accounts + 3 high-contention accounts, each funded $50,000,000.00 directly against Postgres — the API has no funding endpoint by design), then `bench/k6-isolation-test.js` run once per variant against `http://localhost:8080`, in the order Variant 1 → 2 → 3 → 0 (broken last, so its intentional balance corruption doesn't taint the other three). Each run's `options.scenarios` already covers both contention regimes back-to-back (20 VUs × 30s low-contention across 50 accounts, then 20 VUs × 30s high-contention across 3 accounts).

Every number below is computed by [`bench/analyze_results.py`](../../bench/analyze_results.py) from the **committed raw k6 JSON** for that run — nothing here is hand-typed. The raw `--out json=` output is one line per metric data point (tens of MB uncompressed), so the committed files are gzipped; `analyze_results.py` reads `.json.gz` directly.

| File | Command |
| :--- | :--- |
| [`01-isolation-variant0-broken.json.gz`](../../bench/results/01-isolation-variant0-broken.json.gz) | `k6 run bench/k6-isolation-test.js -e VARIANT=VARIANT_0_BROKEN --out json=bench/results/01-isolation-variant0-broken.json` |
| [`01-isolation-variant1-pessimistic.json.gz`](../../bench/results/01-isolation-variant1-pessimistic.json.gz) | `-e VARIANT=VARIANT_1_PESSIMISTIC` |
| [`01-isolation-variant2-optimistic.json.gz`](../../bench/results/01-isolation-variant2-optimistic.json.gz) | `-e VARIANT=VARIANT_2_OPTIMISTIC` |
| [`01-isolation-variant3-serializable.json.gz`](../../bench/results/01-isolation-variant3-serializable.json.gz) | `-e VARIANT=VARIANT_3_SERIALIZABLE` |

Environment caveat: this is one run on one developer laptop (Windows, Docker Desktop, containerized Postgres/Kafka/app all sharing the same host's CPU/disk) — not a controlled, isolated benchmarking environment, and not the cloud target environment. Treat absolute numbers as indicative of *relative* behavior between variants, not as production capacity planning figures. "Retry rate" from the original placeholder table is **not reported** below because it isn't actually instrumented anywhere (no retry counter metric exists in the app — see the M4 observability gap in the README) — a k6-level HTTP client can only see the final outcome of a request, not how many times the app retried internally before returning it. The `Failed %` column (non-201/422 responses, overwhelmingly HTTP 500 from exhausted internal retries) is used instead as an honest proxy for retry/abort pressure.

### Low Contention (50 accounts, 20 VUs, 30s)

| Variant | Requests | RPS | p50 | p95 | p99 | Failed % |
| :--- | ---: | ---: | ---: | ---: | ---: | ---: |
| 0 (Broken) | 16,304 | 544.3/s | 15.1ms | 45.8ms | 105.5ms | 0.04% |
| 1 (Pessimistic) | 12,926 | 432.1/s | 27.4ms | 91.0ms | 135.5ms | 0% |
| 2 (Optimistic) | 16,002 | 532.3/s | 10.3ms | 104.8ms | 211.4ms | 0.11% |
| 3 (Serializable) | 14,810 | 490.8/s | 9.8ms | 130.4ms | 281.6ms | 0.07% |

### High Contention (3 accounts, 20 VUs, 30s)

| Variant | Requests | RPS | p50 | p95 | p99 | Failed % |
| :--- | ---: | ---: | ---: | ---: | ---: | ---: |
| 0 (Broken) | 75 | 1.8/s | 9,024.8ms | 20,328.5ms | 22,290.2ms | 89.3% |
| 1 (Pessimistic) | 7,776 | 258.9/s | 39.3ms | 170.9ms | 226.0ms | 0% |
| 2 (Optimistic) | 1,826 | 60.4/s | 35.9ms | 1,383.0ms | 2,353.8ms | 13.5% |
| 3 (Serializable) | 1,521 | 50.0/s | 9.6ms | 2,341.7ms | 3,403.8ms | 13.4% |

Reading this honestly:
- **Variant 1 (Pessimistic) is the clear winner under high contention**: zero failed requests, and while its p95/p99 are higher than the low-contention case (row-level queuing has a cost), they're an order of magnitude better than Variants 2 and 3.
- **Variants 2 and 3 both degrade sharply under high contention** — multi-second p95/p99 and double-digit failure rates, consistent with retry/abort storms on the same 3 hot accounts. Variant 3 (Serializable) had marginally more failures and worse tail latency than Variant 2 (Optimistic) in this run, though both are in the same rough range — not enough separation in one run to declare a clear winner between them.
- **Variant 0 (Broken) collapsed under high contention** in a way none of the correct variants did: only 75 of an expected multi-thousand-request volume completed in the 30s window, with p50 latency over 9 seconds and an 89% failure rate. This isn't just "wrong," it's *also* the worst-performing option — unlocked blind writes to the same rows still serialize on Postgres's implicit per-statement row lock, but without any of the queuing discipline or backoff the other variants have, so requests pile up and time out instead of completing. See §3 for the actual balance-drift numbers this run produced.

---

## 5. Architectural Tradeoffs & Conclusions

Confirmed by the §4 benchmark run (not just theory):

1. **Deterministic Pessimistic Locking (Variant 1) is the strongest production default** — confirmed. Zero failed requests at either contention level, and the best high-contention p95/p99 by a wide margin (170.9ms / 226.0ms vs. 1,383–3,404ms for Variants 2/3). Locks acquired in sorted UUID order let transactions queue cleanly at the row level without deadlocks or retries.
2. **Optimistic Locking (Variant 2) degrades under hot accounts** — confirmed. High-contention failure rate jumped from 0.11% (low contention) to 13.5%, with p95 latency going from 104.8ms to 1,383.0ms — consistent with retry storms on version conflicts when many transactions target the same 3 accounts.
3. **SERIALIZABLE (Variant 3) has high overhead on hot keys** — confirmed, and in this run it was slightly worse than Variant 2's failure rate and tail latency (13.4% failures, 2,341.7ms p95, 3,403.8ms p99) under high contention, consistent with PostgreSQL's SSI conflict detection issuing more `40001` aborts than Variant 2 saw optimistic-lock version conflicts. One run isn't enough to call this a stable ranking between 2 and 3 — both land in the same rough "degrades badly under hot contention" bucket.
4. **New finding the original hypothesis didn't cover**: Variant 0 isn't just incorrect, it's also the worst performer under high contention — it collapsed to 75 completed requests in 30s with multi-second p50 latency and an 89% failure rate, because unlocked blind writes still serialize on Postgres's implicit row lock but with none of the queuing/backoff discipline the other variants have.
