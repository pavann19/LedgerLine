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
- **Known gap**: a consumer-lag metric (`ledger.projection.consumer.lag`) is *not currently implemented* anywhere in `projection-service`. An earlier version of this ADR claimed it existed and "accurately reflect[ed] downstream progress" — that was aspirational, not shipped. Tracked as future work (see README §9).

---

## ADR 006: Test Failure Scenarios Against Real Dependencies, Not Mocks

### Status
Accepted

### Context
The original `AsyncFailureAndResilienceTest` used `@MockBean KafkaTemplate` to simulate a Kafka outage. A mock only proves the code *calls* `kafkaTemplate.send()` and handles a `CompletableFuture` that some test author decided should fail — it says nothing about how the real Kafka client behaves against connection timeouts, retry/backoff, or actually reconnecting after an outage ends. An audit of the test suite against the project spec flagged this as the weakest part of the M3 failure-testing milestone: the `testcontainers-kafka` and `testcontainers-toxiproxy` dependencies were declared in `pom.xml` but never actually used.

### Decision
1. Replace the mocked `KafkaTemplate` with a real `org.testcontainers.containers.KafkaContainer` in `AsyncFailureAndResilienceTest`.
2. Simulate the broker outage with `docker pause`/`docker unpause` on the running container (via `DockerClientFactory.instance().client()`), not a hard `stop()`/`start()`. A hard restart would reassign the container's mapped port and strand the already-initialized `KafkaTemplate`'s producer client, which was created once at Spring context startup against the original port.
3. Assert on real producer behavior: the send blocks up to its configured timeout while the broker is paused, the outbox backlog does not shrink during the outage, and it fully drains once the broker is unpaused (with a generous `awaitility` window, since a just-unfrozen broker needs real time to finish leader election before it accepts produce requests again).

### Consequences
- The Kafka-down and worker-crash-mid-publish scenarios (`docs/experiments/02-failure-injection.md` §2–3) now exercise the actual Kafka client library's timeout/retry behavior, not a test author's assumption about it.
- Test runtime increased (real container startup + real network timeouts, ~30-90s for the recovery assertion) in exchange for the test actually meaning something.
- Toxiproxy-based DB-outage testing remains an open gap: the dependency is still declared but unused. Tracked as future work.

---

## ADR 007: Multi-Trial Statistical Assertion for the Variant 0 Lost-Update Anomaly

### Status
Accepted

### Context
`IsolationExperimentTest`'s Variant 0 (deliberately unlocked read-then-write) test originally ran the contended workload once and only **logged** whether the cached balance had drifted from the postings-derived balance — it never asserted the anomaly occurred. That meant the test could pass silently even if Variant 0 got accidentally fixed (e.g. by a future refactor that added locking), defeating its purpose as a regression guard for "this is what broken looks like." But the anomaly is a genuine race condition, not a deterministic failure: a single trial can get lucky and interleave cleanly, so naively asserting drift on one run would be flaky in the other direction.

### Decision
1. Run the contended Variant 0 workload across multiple independent trials (10, each against fresh accounts) instead of one.
2. Assert that the drift reproduces in at least one trial (`anomalies >= minObservedAnomalies`), and log the observed anomaly rate (`anomalies/trials`) and total absolute drift for visibility.
3. Document numbers in `docs/experiments/01-isolation.md` as "backed by an assertion in CI," not as a fixed measured figure — the exact drift amount is nondeterministic run-to-run and isn't meaningful to hardcode.

### Consequences
- The test now fails loudly if Variant 0 stops exhibiting the lost-update anomaly, instead of silently degrading into a no-op check.
- Slightly longer test runtime (10 trials vs. 1) in exchange for a real regression guard.

---

## ADR 008: Closing the Cloud Deploy Completeness Gap (ECR + IAM Instance Role + Two-Phase Apply)

### Status
Accepted

### Context
`deploy/main.tf`'s EC2 `user_data` originally only installed Docker — it never pulled or ran the `ledger-service`/`projection-service`/Kafka containers — and `.github/workflows/deploy.yml` ran `terraform apply` but never built or pushed an image, or deployed the app onto the instance. Tagging a release and letting the pipeline run would have produced billed AWS infrastructure with no reachable service on it. This was found and flagged during a documentation-accuracy pass and treated as real unfinished work, not just a doc fix.

### Decision
1. Add two `aws_ecr_repository` resources (`ledgerline/ledger-service`, `ledgerline/projection-service`) to `main.tf`.
2. Add an IAM role + instance profile for the EC2 host, scoped to `AmazonEC2ContainerRegistryReadOnly` (pull images without embedding long-lived credentials on the box) and `AmazonSSMManagedInstanceCore` (reach the instance via SSM Session Manager — no SSH key pair, no open port 22 — to run the smoke test and the invariant SQL queries against RDS, which is intentionally not publicly accessible).
3. Template the EC2 `user_data` (`deploy/cloud-init.sh.tpl`, rendered by Terraform's `templatefile()`) to actually write a Docker Compose file referencing the pushed images by tag, log in to ECR using the instance's IAM role, and `docker compose up -d` — Kafka (KRaft, single node), `ledger-service`, `projection-service`, and Prometheus.
4. `deploy.yml` now does a **two-phase apply**: phase 1 creates only the ECR repositories (`-target=aws_ecr_repository...`, idempotent to re-run) so there's somewhere to push into; then it cross-builds both images for `linux/arm64` (via `docker/setup-qemu-action`, since the app host is a Graviton2 `t4g.small` but GitHub's runners are x86_64) and pushes them tagged with the release tag; then phase 2 does the full `apply`, which bakes that exact image tag into the EC2 `user_data`.
5. Removed the hardcoded default for `db_password` in `main.tf` (it previously defaulted to a fixed string if the variable wasn't passed) — it's now required on every apply, which is safer than a fallback nobody meant to actually use.

### Consequences
- Tagging a release and letting `deploy.yml` run end-to-end now produces an EC2 instance actually serving the ledger and projection APIs, not just bare infrastructure.
- Terraform validated cleanly (`terraform validate`, `terraform fmt -check`) in this environment; the full `apply` itself has not yet been run against a real AWS account (requires AWS credentials this environment doesn't have) — that remains to be executed and its evidence (deploy run, smoke test, load test, invariant queries, real billing cost, teardown timestamp) committed once it is.
