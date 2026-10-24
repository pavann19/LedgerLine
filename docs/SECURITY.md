# Security

Both services validate HS256 JWT signatures and timestamps. A signing secret of at least 32 bytes is required; entropy and secret distribution are operator responsibilities. Required claims are nonblank sub (without the internal key separator), exp, and roles. Every holder of the shared secret can mint OPERATOR tokens. Login, passwords, refresh tokens, revocation, and issuer/audience restriction are not implemented.

CUSTOMER can create customer accounts, read owned accounts/postings, and debit owned accounts. OPERATOR can provision OVERDRAFT/INTERNAL accounts and access all accounts. Destinations need not belong to the sender. No account enumeration, historical mutation, admin login, closure, or debug endpoint exists.

Projection customers require the account UUID in the signed accounts claim. The projection service does not query current ledger ownership. Token issuers must supply correct short-lived claims; ownership reassignment/revocation is unsupported. Operators bypass claim checks.

Health and OpenAPI are public. All other paths default to denied, including metrics despite actuator exposure configuration. Prometheus needs an explicit authenticated operational integration before successful scraping is claimed.

Controller calls derive identity and role from the security context. Trusted internal service overloads use SYSTEM/legacy scope for fixtures and experiments and are not exposed as HTTP endpoints. Do not expose them through another transport without authorization.

SQL administrators and Kafka producers are trusted. Local Compose uses the PostgreSQL owner and plaintext Kafka. UPDATE/DELETE triggers protect postings, transaction headers, and audit records; owners can disable triggers, TRUNCATE, or change balances directly. No tamper-evident audit chain or request correlation ID exists.

Pagination is bounded to 100 rows. Request-size limits beyond Tomcat defaults, rate limiting, TLS termination, payment callbacks, and rotation are unsupported. Compose binds host ports to localhost and contains local-only PostgreSQL/Grafana credentials. AWS examples require a reviewer CIDR but still expose HTTP and place secrets in Terraform state/user data. Shell tracing is disabled; this is not a secret-manager implementation. Production and real-money deployment are unverified.

Java target 21 and Maven 3.9.9 are pinned. Dependency versions come from the Boot BOM plus explicit overrides. Actions use resolved Git SHAs; default permissions are contents:read. Raw Trivy HIGH/CRITICAL findings remain visible. A separate blocking gate evaluates them against exact-version, expiring applicability decisions supported by both services' MVC regression receipts; unrelated or unreviewed findings fail. See ../security/README.md. Mutable image tags, apk upgrade inputs, and wrapper archive integrity limit full reproducibility. Dependency upgrades alone do not prove a clean image scan.

Report vulnerabilities privately to the repository owner through GitHub; do not post tokens, signing secrets, or financial data publicly.
