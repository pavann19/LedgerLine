# Database and deployment

PostgreSQL 16 persists accounts, balance rows, transaction headers, signed postings, outbox, and audit history. UUIDs identify accounts/transactions; BIGSERIAL identifies postings/outbox/audit; TIMESTAMPTZ stores timestamps; money uses BIGINT. Projection stores processed event IDs, statement rows, and UTC summaries. Local services share a database with separate Flyway history tables.

V1 creates tables, FKs, indexes, and nonnegative balances. V2 adds deferred zero-sum and posting immutability. V3 adds ownership and principal-scoped uniqueness, assigning old rows LEGACY_SYSTEM. V4 adds enum/type/currency consistency, nonzero/unique postings per transaction-account, single-currency zero-sum, and header/audit immutability. V1-V3 remain unchanged. V4 fails on invalid existing data rather than repairing or deleting it. Back up and inspect existing databases; fresh/upgrade execution requires database-backed verification.

No account deletion, scheduled monetary mutation, interest, FX, or rounding exists. The outbox poller is the scheduled background work. JDBC parameters prevent SQL injection in the reviewed queries. Pagination bounds results; larger datasets may benefit from (account_id,id) indexes. Query plans and large-history throughput are unverified.

Compose uses known local PostgreSQL/Grafana credentials and binds published ports to 127.0.0.1. Kafka advertises its container name; use container clients or Testcontainers, not an unsupported host-native quickstart. Tests start separate databases.

Docker tags and apk upgrade inputs are mutable; containers run as root. Production requires TLS, least-privilege runtime DB roles separate from migrations, authenticated Kafka, backups/recovery, resource limits, and immutable digests. These are unsupported operations, not shipped guarantees.

AWS Terraform/workflow are optional short-lived evidence examples. REVIEWER_CIDR is required alongside DB/JWT secrets. They expose HTTP, use privileged database credentials, and destroy resources with skip_final_snapshot/force_delete. Protect bearer tokens through a tunnel. The AWS template remains unexecuted in this release. No production recovery evidence exists.

Projection V2 adds the composite (account_id,created_at,id) index used for predecessor lookup and late-event repairs. Compose waits for ledger health before starting projection, preventing the two Flyway histories from racing to initialize an empty shared schema. Kafka readiness still requires a successful broker query; its JVM-based health command has a 15-second timeout and a bounded cold-start allowance. These local startup paths were executed in the evaluator; AWS template changes remain unexecuted.

## Temporary Azure verification

`scripts/azure-evidence-run.ps1` provisions a separate temporary resource group with a private image registry, managed PostgreSQL 16 and a Linux VM. It requires an authenticated Azure CLI, Docker, OpenSSH and the Bash path configured in the script. This is a paid operator-run check; it is never started by the normal CI workflow.

The runner restricts SSH ingress to the operator's current IPv4 address and sends local API traffic through an SSH tunnel. The services bind to VM loopback; Kafka has no published host port. PostgreSQL permits the operator and VM addresses rather than all addresses. It waits for ledger health before starting projection. The cloud API probe runs on the VM, derives a short-lived test token locally and reports assertions without emitting the signing secret.

Evidence goes to a separate temporary directory unless `-EvidenceDirectory` is supplied. Protect those files: cloud resource metadata and raw operator logs are private. A budget amount is an attempted accounting configuration, not a hard spending cap; unsupported budget API calls are recorded explicitly. The runner requests resource-group deletion in `finally` and verifies absence. Interruption or cleanup failure requires checking the recorded resource group manually; do not equate a deletion request with confirmed cleanup. It does not delete pre-existing resource groups.
