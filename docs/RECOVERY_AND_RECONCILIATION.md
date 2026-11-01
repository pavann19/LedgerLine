# Recovery and reconciliation interfaces

Poison Kafka batches retry as whole transactions. Recovery parks each record in
projection_quarantine before acknowledging its offset. Parking failure leaves the
offset uncommitted; duplicate parking checks the immutable coordinate and payload
hash. Quarantine is a durable unresolved obligation, not successful projection.
Raw quarantined payloads require restricted operator access and never enter prompts.

Apply projection V5 and V6 with the matching consumer/service code. V5 adds durable
quarantine; V6 wraps the existing V4 batch function and adds correlation and aggregate
views. Ledger V5 adds an aggregate view. Existing applied migrations are unchanged.
Do not deploy the consumer without these migrations. Forward-only Flyway migrations
have no destructive down migration: retain schema on application rollback and test
the exact old binary against it in a fixture. Never drop quarantine to unblock traffic.
Replay/correction is a separate audited operator operation; no repair API is supplied.

The optional GET /api/v1/ops/reconciliation requires RECONCILER and is disabled unless
reconciliation.enabled=true. Configure reconciliation.jdbc-url, .username and .password
with a separate view-only identity; there is no fallback to the application pool.
Operator provisioning is documented in deploy/reconciliation-role.sql. Both services
must have migrated before granting both views. Use a dedicated login, connection limit,
statement timeout and read-only default; never grant base tables or role administration.
Ordinary CUSTOMER/OPERATOR tokens cannot access these views; RECONCILER alone cannot
use customer APIs. Retain the canonical OAuth2 issuer/audience checks and rate limiting.

Ledger aggregate schema is v1. Projection HTTP schema is v2 and reports unknown broker
lag as consumer_lag_known=0 and null consumer_lag. Unknown blocks analysis. Aggregates
are sequential point-in-time observations, not a cross-database atomic snapshot.
Positive pending work or quarantine requires investigation, not a model override.

Correlation IDs are application-generated, carried in outbox payloads and batch trace
rows; no arbitrary inbound header is trusted. Legacy seven-field events default to
their transaction ID. Strict invalid-event validation can park previously malformed
traffic; inspect obligations after upgrade. Graceful shutdown is now enabled.

Bounded verification from a copied checkout with Java 21 and a disposable Docker daemon:

```sh
./mvnw clean verify -Pcoverage --batch-mode
```

Tests allocate fresh PostgreSQL/Kafka containers; never mount production data or existing
Compose volumes. Regressions cover DB timeout/deadlock rollback, poison parking failure,
duplicate delivery, drain, restore, legacy payloads, unknown lag and actual view-only roles.
The canonical provider-mode HTTP tests also check the new role boundary.

Historical local original two-account load failed p95 1155.68ms / p99 1901.30ms against
unchanged <750 / <1500ms gates. Separate many-account results cannot substitute. The
cause is unproven. No 24-hour soak, production capacity or fresh cloud result is claimed.
Earlier Surefire forced-fork shutdown warnings remain a limitation. Fresh candidate
validation is distinct from those historical observations and from hosted CI.
