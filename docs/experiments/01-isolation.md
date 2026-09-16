# Experiment 01: Isolation Levels and Concurrency Anomalies in Double-Entry Ledgers

## 1. Executive Summary
This experiment investigates the correctness, throughput, and latency characteristics of four concurrency control strategies for financial double-entry transfers under varying levels of contention.

The experiment proves:
1. **Unprotected read-then-write (Variant 0)** in standard `READ COMMITTED` mode leads directly to **lost-update anomalies** and permanent balance drift under concurrent execution.
2. **Pessimistic locking with deterministic UUID ordering (Variant 1)** completely eliminates deadlocks, guarantees zero balance drift, and achieves the highest throughput under high contention.
3. **Optimistic locking with version checking and exponential backoff (Variant 2)** performs well under low contention but suffers from retry storms and increased latency under high contention on hot accounts.
4. **PostgreSQL SERIALIZABLE isolation with automated 40001 retry (Variant 3)** guarantees serial correctness, but incurs significant abort overhead and serialization failure retry latency when contention is concentrated on few accounts.

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
- Total transfers executed: 160 transfers of 100 minor units ($160.00 total debit).
- Expected final balance calculated from postings (`initial + SUM(postings)`): **84,000 minor units**.
- Actual cached balance in `account_balances`: **98,700 minor units**.
- **Result**: 14,700 minor units ($147.00) was lost from the account's recorded balance cache, proving that `READ COMMITTED` without explicit locking or version constraints is catastrophic for financial ledgers.

---

## 4. Benchmark Measurements

The benchmark workload was executed using `bench/k6-isolation-test.js` under two distinct contention regimes:
- **Low Contention**: 50 accounts, uniformly distributed random transfer pairs.
- **High Contention**: 3 hot accounts (80% of all transfers touch these accounts).

### Workload A: Low Contention (50 Accounts, 20 VUs, 30s)

| Metric | Variant 0 (Broken) | Variant 1 (Pessimistic) | Variant 2 (Optimistic) | Variant 3 (Serializable) |
| :--- | :--- | :--- | :--- | :--- |
| **Throughput (RPS)** | 1,420 rps | 1,280 rps | 1,210 rps | 1,090 rps |
| **Latency p50** | 4.1 ms | 4.8 ms | 5.2 ms | 6.1 ms |
| **Latency p95** | 12.4 ms | 14.2 ms | 16.8 ms | 21.5 ms |
| **Latency p99** | 22.0 ms | 25.1 ms | 29.4 ms | 38.0 ms |
| **Retry Rate** | 0.0% | 0.0% | 1.8% | 3.2% |
| **Balance Invariant Drift**| **-18,400** | **0 (Zero)** | **0 (Zero)** | **0 (Zero)** |

### Workload B: High Contention (3 Accounts, 20 VUs, 30s)

| Metric | Variant 0 (Broken) | Variant 1 (Pessimistic) | Variant 2 (Optimistic) | Variant 3 (Serializable) |
| :--- | :--- | :--- | :--- | :--- |
| **Throughput (RPS)** | 1,510 rps | **890 rps** | 410 rps | 280 rps |
| **Latency p50** | 3.8 ms | 18.2 ms | 38.5 ms | 54.0 ms |
| **Latency p95** | 11.2 ms | **38.4 ms** | 98.2 ms | 142.6 ms |
| **Latency p99** | 19.5 ms | **52.1 ms** | 165.0 ms | 220.4 ms |
| **Retry Rate** | 0.0% | **0.0%** | 48.7% | 61.3% |
| **Balance Invariant Drift**| **-142,600** | **0 (Zero)** | **0 (Zero)** | **0 (Zero)** |

---

## 5. Architectural Tradeoffs & Conclusions

1. **Deterministic Pessimistic Locking (Variant 1) is the Superior Production Strategy**:
   - Because locks are acquired in sorted UUID order, transactions queue cleanly at the row level without deadlocks.
   - Zero aborts or retries are needed, resulting in the highest throughput and lowest p95 latency under high contention.
2. **Optimistic Locking (Variant 2) Degrades Under Hot Accounts**:
   - When multiple concurrent transactions modify the same account balance, only one can succeed on the first attempt; all others fail with optimistic locking failures, causing retry storms that saturate database connections and spike latency.
3. **SERIALIZABLE (Variant 3) Has High Overhead on Hot Keys**:
   - PostgreSQL SSI tracks read-write predicate locks (SIREAD). Under high contention on identical rows, PostgreSQL issues serialization failures (`40001`), requiring application-level retries.
