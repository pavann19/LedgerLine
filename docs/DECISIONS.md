# Architecture Decision Records (ADRs) - Ledgerline

This document records the core architectural decisions made in the design and implementation of the **Ledgerline** double-entry financial ledger service.

---

## ADR 001: Double-Entry Bookkeeping and Minor Unit Representation

### Status
Accepted

### Context
Financial services handling currency transfers cannot tolerate lost, created, or unbalanced monetary units. Standard CRUD balance updates (`UPDATE accounts SET balance = balance - X`) suffer from lack of auditability, drift, and balance recreation failures. Floating-point arithmetic (`float`, `double`) introduces catastrophic IEEE 754 precision errors.

### Decision
1. Represent all money as integer minor units (`long` / PostgreSQL `BIGINT`). Floating point arithmetic is strictly banned from the codebase.
2. Every transfer creates immutable, balanced double-entry `postings` where:
   $$\sum_{i} \text{amount\_minor}_i = 0$$
3. Enforce this zero-sum invariant both at the application domain layer and at the database layer using a PostgreSQL **deferred constraint trigger** (`CONSTRAINT TRIGGER ... DEFERRABLE INITIALLY DEFERRED`).
4. Maintain `account_balances` as an atomic write-side cache checked by a database constraint:
   $$\text{CHECK} \ (\text{account\_type} = \text{'OVERDRAFT'} \lor \text{balance\_minor} \ge 0)$$

### Consequences
- Unbalanced transactions can never be committed; PostgreSQL aborts any transaction violating the zero-sum trigger.
- Full auditability: account balances can be recalculated from genesis at any time by summing postings.

---

## ADR 002: Deterministic UUID Lock Ordering for Deadlock Elimination

### Status
Accepted

### Context
When two concurrent transfers occur between the same pair of accounts in opposite directions (e.g., $A \to B$ and $B \to A$), naive locking orders (`FOR UPDATE`) cause database deadlocks ($A$ waits for $B$ while $B$ waits for $A$), triggering PostgreSQL error `40P01` (`deadlock_detected`) and transaction rollbacks.

### Decision
Sort account IDs in strict lexicographical order prior to acquiring row-level locks:
$$\text{firstLock} = \min(\text{fromId}, \text{toId}), \quad \text{secondLock} = \max(\text{fromId}, \text{toId})$$
Acquire pessimistic locks in that deterministic sequence:
```sql
SELECT * FROM account_balances WHERE account_id = :firstLock FOR UPDATE;
SELECT * FROM account_balances WHERE account_id = :secondLock FOR UPDATE;
```

### Consequences
- Deadlocks between competing transfers are eliminated by design (proven by Coffman deadlock conditions: removing circular wait).
- Extremely high concurrency throughput even on contentious account pairs.

---

## ADR 003: Idempotency Key with SHA-256 Request Hash Verification

### Status
Accepted

### Context
Network unreliability causes clients to retry requests. A robust financial API must guarantee that retrying a transfer never results in double-debiting, and that reusing an idempotency key with conflicting arguments is rejected.

### Decision
1. Require an `Idempotency-Key` header on `POST /transfers`.
2. Compute a SHA-256 hash of the normalized request parameters:
   $$\text{request\_hash} = \text{SHA-256}(\text{fromAccountId} \parallel \text{toAccountId} \parallel \text{amountMinor} \parallel \text{currency})$$
3. When receiving a request:
   - Attempt to insert into `transactions(id, idempotency_key, request_hash, ...)`.
   - On unique constraint violation (`23505`), fetch existing record:
     - If stored `request_hash == incoming_hash`: return original stored response (`HTTP 200 OK`, `idempotentReplay = true`).
     - If stored `request_hash != incoming_hash`: return `HTTP 422 Unprocessable Entity` (`IDEMPOTENCY_CONFLICT`).

### Consequences
- Prevents double-spending under concurrent retries.
- Detects client parameter mismatches and security tampering.

---

## ADR 004: Transactional Outbox Poller with `SKIP LOCKED`

### Status
Accepted

### Context
Publishing directly to Apache Kafka within the same database transaction creates a dual-write hazard: if Kafka fails, the DB commits but the message is lost; if Kafka succeeds but the DB rolls back, phantom events are published.

### Decision
1. Implement the Transactional Outbox pattern: the transfer database transaction inserts an outbox event row into `outbox(aggregate_id, event_type, payload)` atomically with the ledger postings and balances.
2. An Outbox Relay poller queries unpublished rows using:
   ```sql
   SELECT * FROM outbox 
   WHERE published_at IS NULL 
   ORDER BY id ASC 
   FOR UPDATE SKIP LOCKED 
   LIMIT :batchSize
   ```
3. Relay publishes to Kafka (`topic: ledger.transfers.v1`, key: `aggregate_id`, `acks=all`, `enable.idempotence=true`).
4. Upon successful broker acknowledgment, execute:
   ```sql
   UPDATE outbox SET published_at = clock_timestamp() WHERE id = :id
   ```

### Consequences
- Guarantees at-least-once message delivery to Kafka.
- Multiple competing relay instances scale horizontally without lock contention or duplicated work due to `SKIP LOCKED`.
- Decouples synchronous API write latency from Kafka broker availability.

---

## ADR 005: Projection Idempotency and Consumer Offset Demarcation

### Status
Accepted

### Context
Kafka provides at-least-once delivery. If the projection consumer crashes after updating the read-model database but before committing its Kafka offset, the message will be redelivered upon restart.

### Decision
1. Maintain a deduplication table in the projection database:
   `processed_events(event_id UUID PRIMARY KEY, processed_at TIMESTAMPTZ)`
2. In the consumer's database transaction:
   - Execute `INSERT INTO processed_events (event_id) VALUES (?) ON CONFLICT DO NOTHING`.
   - If conflict occurs (0 rows inserted), abort projection updates and exit cleanly.
   - If novel (1 row inserted), update `statement_view` and `daily_account_summary`.
3. Commit Kafka consumer offset (`ack.acknowledge()`) **strictly after** the database transaction has committed.

### Consequences
- Exactly-once read-model projection semantics despite network partitions, consumer crashes, and message redeliveries.
- Consumer lag metrics (`ledger.projection.consumer.lag`) accurately reflect downstream progress.
