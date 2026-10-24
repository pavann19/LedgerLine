# Architecture

Two Spring Boot services use JDBC and PostgreSQL. The ledger service is the synchronous balance authority; the projection service consumes Kafka into eventual statements and daily summaries. There is no ORM, cache, payment gateway, FX provider, or distributed transaction.

## Mutation traces

Account creation: JWT/role filter -> DTO validation -> non-customer provisioning restriction -> transactional service proxy -> account + zero balance + audit -> commit -> response.

Transfer: JWT/role filter -> DTO validation -> source ownership -> principal-scoped key and hash -> committed replay check -> strategy transaction -> replay check -> sorted FOR SHARE account metadata locks + status/currency revalidation -> balance reads/locks -> funds check -> header + debit + credit + outbox + audit -> checked balance writes -> deferred constraints -> commit -> response. Pessimistic execution rechecks replay after acquiring balance locks.

All JDBC repositories use the Spring transaction-bound connection. Default READ COMMITTED execution acquires two balance FOR UPDATE locks in Java UUID order. Optimistic execution checks versions and retries at most five conflicts; SERIALIZABLE uses explicit isolation and retries SQLSTATE 40001/40P01 at most five times. Retry exhaustion is a failure. Mixing strategies can deadlock; sorted pessimistic locks do not prove system-wide deadlock freedom. Variant 0 deliberately performs unsafe unlocked updates and remains internal only.

Metadata locks block direct SQL status changes during transfers. No closure API exists. Ownership is checked before the strategy; changing ownership by direct SQL during a transfer is outside the supported API contract.

## Event boundary

The scheduled relay locks unpublished outbox rows with SKIP LOCKED, waits up to five seconds per Kafka send, then records publication in PostgreSQL. A send followed by database failure can redeliver. Kafka keys are transaction IDs, so different transfers for one account can arrive out of order. Producer sends are not atomically committed with PostgreSQL.

The consumer commits event-ID deduplication and projection writes before acknowledging its Kafka offset. Sorted advisory account locks serialize account projection updates. Running balances and UTC summaries follow (created_at,id) order; equal timestamps use insertion ID, not a proven business sequence. In-order events append using indexed predecessor lookups and update one daily summary. Late events shift only later statement balances and subsequent daily opening/closing balances. Late events can still require substantial work when they precede a large history. Projection tables are not the money authority.

Idempotency is serialized before mutable reads by a PostgreSQL transaction-scoped advisory lock on the scoped key. Hash collisions cause additional serialization, not shared replay. There is no explicit transfer lock/transaction timeout; the Hikari connection timeout is not a transaction deadline.
