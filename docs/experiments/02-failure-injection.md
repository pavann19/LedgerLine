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
| **Worker Crash Mid-Publish** | `OutboxRelayPoller.setSimulateCrashAfterSend(true)` throws after the real Kafka send before marking `published_at` | Outbox row remains unpublished; next `pollAndPublish()` call re-sends and marks it published | Event re-published exactly once recovery runs; dedup of a duplicate delivery verified separately | **VERIFIED (two tests, not one end-to-end run — see 3.1 known gap)** |
| **Message Redelivery** | `ProjectionDeduplicationTest` applies the identical event twice directly against the projection service (real Postgres) | Second application is rejected by `processed_events(event_id PK)` | Read-model balance not incremented twice | **VERIFIED** |
| **Kafka Broker Outage** | Real Kafka Testcontainer broker paused (`docker pause`) mid-test, not mocked | Synchronous `POST /transfers` writes to ledger & outbox tables without blocking on Kafka | Synchronous transfers continue committing; outbox backlog accumulates while paused; drains to zero after unpause | **VERIFIED** |
| **Outbox Competing Relays** | Multiple concurrent pollers running `FOR UPDATE SKIP LOCKED` | Each poller locks distinct disjoint batches of outbox events | No duplicate events picked up simultaneously across worker instances | **VERIFIED** |
| **Arbitrary Trace Sequences** | jqwik property-based generator, 20 tries x up to 15 actions, run against the real `TransferService` + a real Postgres Testcontainer (not an in-memory model) | Permutations of valid transfers, duplicate-key retries, invalid amounts, overdraft attempts | All double-entry invariants (conservation, non-negative balances, postings sum == cached balance) hold, re-derived from the real `postings`/`account_balances` tables after every action | **VERIFIED** |

---

## 3. Detailed Failure Scenarios & Analysis

### 3.1 Worker Crash Mid-Publish
- **Fault**: The outbox relay succeeds in calling `kafkaTemplate.send(topic, key, payload).get()`, but the relay process is abruptly terminated before executing `UPDATE outbox SET published_at = now()`. Reproduced directly via the `OutboxRelayPoller.setSimulateCrashAfterSend(true)` test hook, which throws immediately after the send succeeds.
- **System Recovery**:
  1. The row in `outbox` remains with `published_at IS NULL`. Note: `pollAndPublish()` catches the simulated-crash exception internally (so one bad event can't kill the `@Scheduled` poller thread or the whole batch) — the poll transaction still commits normally, it just never reaches the `markPublished()` call for that event, which is what leaves the row unpublished.
  2. The next call to `pollAndPublish()` locks the row and re-sends the event to Kafka.
  3. A downstream projection consumer receiving the duplicate event with the same `eventId` UUID would attempt `INSERT INTO processed_events (event_id) VALUES (:eventId) ON CONFLICT DO NOTHING`, get 0 rows inserted, and skip the balance update — this half of the flow is covered separately by `ProjectionDeduplicationTest`, which calls the dedup path directly rather than through a live relay/consumer pair.
- **Evidence**: `AsyncFailureAndResilienceTest.shouldRepublishAfterSimulatedCrashBetweenSendAndMarkPublished` (real Postgres via Testcontainers) asserts the outbox row stays unpublished immediately after the simulated crash and is successfully published on the next poll. `ProjectionDeduplicationTest.shouldDeduplicateIdenticalEventsUnderMessageRedelivery` separately asserts the dedup side: applying the same event twice leaves `statement_view`/`daily_account_summary` unchanged after the first application.
- **Known gap**: these are two separate tests, not one end-to-end run through a live relay + live consumer. No test currently drives a crash-then-republish through an actual running `TransferEventConsumer` to observe the dedup happen live.

### 3.2 Kafka Broker Down
- **Fault**: The real Kafka broker (Testcontainers `KafkaContainer`) is frozen mid-test via `docker pause` (`AsyncFailureAndResilienceTest.shouldAccumulateOutboxBacklogWhenKafkaIsDownAndDrainAfterRecovery`), not a mocked `KafkaTemplate`. Pause (not stop+restart) is used so the broker's container/port stay stable for the already-initialized producer client to reconnect to once unpaused.
- **System Recovery**:
  1. Client calls `POST /transfers`.
  2. The database transaction executes completely: transaction row, postings, balance updates, and outbox event are committed.
  3. The API immediately responds with `HTTP 201 Created`. The API does not depend on Kafka for write durability.
  4. With the broker paused, `outboxRelayPoller.pollAndPublish()` fails its Kafka send attempt and leaves the outbox row unpublished; the backlog count is asserted to not shrink.
  5. Once the broker is unpaused, polling again drains the backlog to zero.
- **Evidence**: `AsyncFailureAndResilienceTest.shouldAccumulateOutboxBacklogWhenKafkaIsDownAndDrainAfterRecovery` asserts: the transfer commits and the backlog is unaffected while paused, then fully drains after recovery.

### 3.3 Database Deferred Trigger: Zero-Sum Protection
- **Fault**: Defective or malicious code attempts to commit an unbalanced transaction (e.g. inserting only a debit posting without a matching credit posting).
- **System Recovery**:
  1. The posting is inserted into PostgreSQL within a transaction block.
  2. Because the constraint trigger is defined with `DEFERRABLE INITIALLY DEFERRED`, PostgreSQL evaluates the trigger during the `COMMIT` phase.
  3. The trigger executes `SELECT COALESCE(SUM(amount_minor), 0) FROM postings WHERE transaction_id = NEW.transaction_id`.
  4. The sum evaluates to non-zero, raising a PostgreSQL exception.
  5. The entire transaction is aborted and rolled back.
- **Evidence**: `DatabaseConstraintInvariantTest.shouldRejectUnbalancedPostingsAtCommit` confirms PostgreSQL rolls back the unbalanced posting.
