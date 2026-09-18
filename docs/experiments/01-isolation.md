# Experiment 01: Isolation Levels and Concurrency Anomalies in Double-Entry Ledgers

## 1. Executive Summary
This experiment investigates the correctness, throughput, and latency characteristics of four concurrency control strategies for financial double-entry transfers under varying levels of contention.

**Status: correctness claims below are backed by committed, executable tests (§2, §3). Throughput/latency claims are not yet backed by a committed benchmark run — see §4 — and should be read as expected behavior from the locking mechanism, not as measured results.**

What the committed tests demonstrate:
1. **Unprotected read-then-write (Variant 0)** in standard `READ COMMITTED` mode leads directly to **lost-update anomalies** and balance drift under concurrent execution — asserted by `IsolationExperimentTest.demonstrateLostUpdateAnomalyInVariant0`.
2. **Pessimistic locking with deterministic UUID ordering (Variant 1)**, **optimistic locking with version retry (Variant 2)**, and **SERIALIZABLE with 40001 retry (Variant 3)** all preserve exact zero balance drift under concurrent contention — each asserted by its own test in `IsolationExperimentTest`.

What is expected but not yet measured (pending §4):
- Variant 1 is expected to have the lowest p95 latency and fewest aborts under hot-account contention, since it queues at the row level with zero retries.
- Variant 2 is expected to degrade under high contention on the same accounts (retry storms on version conflicts).
- Variant 3 is expected to show the highest abort/retry overhead under contention concentrated on few accounts, from PostgreSQL's SSI conflict detection.

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

### Empirical Test Result
`IsolationExperimentTest.demonstrateLostUpdateAnomalyInVariant0` (`ledger-service/src/test/java/com/ledgerline/ledger/isolation/IsolationExperimentTest.java`) runs the contended Variant-0 workload across 10 independent trials against fresh accounts each time, and **asserts** — not just logs — that the drift reproduces in at least 1 of the 10 trials. It logs the observed anomaly rate (`anomalies/trials`) and the total absolute drift on each CI run; those numbers are not reproduced here as fixed figures because a race condition's exact drift is nondeterministic run-to-run. The committed evidence for this scenario is the test itself and its pass/fail result in CI, not a hand-typed number in this document.

---

## 4. Benchmark Measurements

**Not yet run.** `bench/k6-isolation-test.js` exists and is intended to exercise this workload under low- and high-contention regimes across all four variants, but it has not actually been executed against a live deployment from this environment, and no raw k6 JSON output is committed to the repo. The table that previously appeared here listed specific throughput/latency/retry-rate figures with no corresponding run artifact — those numbers were unverifiable and have been removed rather than left in place or reissued as another guess.

To populate this section for real:
1. Run `k6 run bench/k6-isolation-test.js --out json=bench/results/01-isolation-<date>.json` against a running `ledger-service` instance, once per contention regime.
2. Commit the raw JSON output under `bench/results/`.
3. Only then fill in this table, with each cell linking back to the specific committed JSON file it was read from.

---

## 5. Architectural Tradeoffs & Conclusions

These are theoretical/expected conclusions based on how each locking mechanism works, offered as the hypothesis the §4 benchmark run is meant to test — they are not yet confirmed by a committed measurement.

1. **Deterministic Pessimistic Locking (Variant 1) is expected to be the strongest production default**:
   - Because locks are acquired in sorted UUID order, transactions queue cleanly at the row level without deadlocks.
   - No aborts or retries are needed in principle, which should give it the highest throughput and lowest p95 latency under high contention — to be confirmed by §4.
2. **Optimistic Locking (Variant 2) is expected to degrade under hot accounts**:
   - When multiple concurrent transactions modify the same account balance, only one can succeed on the first attempt; the rest fail the version check and retry, which should show up as retry storms and increased latency under high contention — to be confirmed by §4.
3. **SERIALIZABLE (Variant 3) is expected to have the highest overhead on hot keys**:
   - PostgreSQL SSI tracks read-write predicate locks (SIREAD). Under high contention on identical rows, PostgreSQL is expected to issue more serialization failures (`40001`) than Variant 2 sees version conflicts, requiring more application-level retries — to be confirmed by §4.
