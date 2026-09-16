# Experiment 02: Failure Injection, Chaos Scenarios, and System Invariants

## 1. Overview
Ledgerline guarantees correctness under unreliability: crashes, message duplication, network partitions, and infrastructure outages. This document records the empirical outcomes of our failure injection test suite.

---

## 2. Invariant Assertion Matrix

| Scenario | Injection Mechanism | System Behavior Under Fault | Invariant Verified | Result |
| :--- | :--- | :--- | :--- | :--- |
| **Duplicate Request Storm** | 20 concurrent threads with identical `Idempotency-Key` | Only 1 transaction inserts; other 19 block on unique constraint then return cached result | Exactly 1 transaction created; 2 postings; identical responses returned | **VERIFIED** |
| **Idempotency Hash Mismatch** | Re-using key with altered amount/account/currency | SHA-256 hash mismatch detected against stored transaction record | Rejection with `HTTP 422 Unprocessable Entity`; state unchanged | **VERIFIED** |
| **Concurrent Randomized Transfers** | 50 threads x 20 transfers across 20 accounts | Deterministic UUID lock ordering prevents deadlocks | Conservation of money: $\sum \text{balances} = \text{initial}$; no negative balances; $\sum \text{postings} = \text{balances}$ | **VERIFIED** |
| **Worker Crash Mid-Publish** | Test hook throws after Kafka send before marking `published_at` | Outbox row remains unpublished; poller retries on next schedule | Event re-delivered to Kafka; projection dedup table (`processed_events`) drops duplicate | **VERIFIED** |
| **Message Redelivery** | Consumer simulated crash after DB commit before offset commit | Consumer restarts and re-reads message from Kafka partition | `processed_events(event_id PK)` conflict detected; read-model balance not incremented twice | **VERIFIED** |
| **Kafka Broker Outage** | Kafka broker stopped/unreachable | Synchronous `POST /transfers` writes to ledger & outbox tables without blocking on Kafka | Synchronous transfers continue committing; outbox backlog accumulates; drains upon broker recovery | **VERIFIED** |
| **Outbox Competing Relays** | Multiple concurrent pollers running `FOR UPDATE SKIP LOCKED` | Each poller locks distinct disjoint batches of outbox events | No duplicate events picked up simultaneously across worker instances | **VERIFIED** |
| **Arbitrary Trace Sequences** | jqwik property-based generator running 1,000 operation sequences | Permutations of valid transfers, duplicate retries, invalid amounts, overdraft attempts | All double-entry invariants hold against in-memory double-entry oracle | **VERIFIED** |

---

## 3. Detailed Failure Scenarios & Analysis

### 3.1 Worker Crash Mid-Publish
- **Fault**: The outbox relay succeeds in calling `kafkaTemplate.send(topic, key, payload).get()`, but the relay process is abruptly terminated before executing `UPDATE outbox SET published_at = now()`.
- **System Recovery**:
  1. The row in `outbox` remains with `published_at IS NULL`.
  2. The next execution of the poller locks the row and re-sends the event to Kafka.
  3. The projection consumer receives the duplicate event with the same `eventId` UUID.
  4. The projection transaction attempts `INSERT INTO processed_events (event_id) VALUES (:eventId) ON CONFLICT DO NOTHING`.
  5. The query returns 0 rows inserted. The projection service detects the duplicate and skips applying the balance update.
  6. The Kafka consumer acknowledges the offset.
- **Evidence**: `ProjectionDeduplicationTest.shouldDeduplicateIdenticalEventsUnderMessageRedelivery` verifies `statement_view` contains exactly 1 row and `daily_account_summary.posting_count == 1`.

### 3.2 Kafka Broker Down
- **Fault**: Kafka container is stopped (`docker stop ledgerline-kafka`) while client applications submit transfers.
- **System Recovery**:
  1. Client calls `POST /transfers`.
  2. The database transaction executes completely: transaction row, postings, balance updates, and outbox event are committed.
  3. The API immediately responds with `HTTP 201 Created`. The API does not depend on Kafka for write durability.
  4. The outbox relay fails its Kafka send attempt, logs an error, and leaves the outbox row unpublished.
  5. `ledger.outbox.backlog.size` metric rises.
  6. When Kafka container restarts, the next poller schedule publishes all accumulated backlog events in order and marks them published.
- **Evidence**: `AsyncFailureAndResilienceTest.shouldAccumulateOutboxBacklogWhenKafkaIsDown` verifies backlog increases and transfer succeeds.

### 3.3 Database Deferred Trigger: Zero-Sum Protection
- **Fault**: Defective or malicious code attempts to commit an unbalanced transaction (e.g. inserting only a debit posting without a matching credit posting).
- **System Recovery**:
  1. The posting is inserted into PostgreSQL within a transaction block.
  2. Because the constraint trigger is defined with `DEFERRABLE INITIALLY DEFERRED`, PostgreSQL evaluates the trigger during the `COMMIT` phase.
  3. The trigger executes `SELECT COALESCE(SUM(amount_minor), 0) FROM postings WHERE transaction_id = NEW.transaction_id`.
  4. The sum evaluates to non-zero, raising a PostgreSQL exception.
  5. The entire transaction is aborted and rolled back.
- **Evidence**: `DatabaseConstraintInvariantTest.shouldRejectUnbalancedPostingsAtCommit` confirms PostgreSQL rolls back the unbalanced posting.
