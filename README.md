# LedgerLine

LedgerLine is a Java 21 / Spring Boot 4.0 financial-backend demonstration. It records same-currency account transfers as signed integer minor-unit postings in PostgreSQL, then publishes a transactional outbox to Kafka for asynchronous statements and daily summaries.

It is a portfolio implementation with executable invariants. Production operation, high availability, banking compliance, and universal performance guarantees are **UNVERIFIED**.

## Financial model

Accounts start at zero. Customers may create `CUSTOMER` accounts and debit only accounts they own. Operators may create `OVERDRAFT` and `INTERNAL` accounts and access all accounts. Only `OVERDRAFT` permits a negative balance; demo funding is a balanced transfer from an operator-created overdraft account. There is no deposit, withdrawal, closure, refund, FX, or reversal endpoint.

Amounts are positive signed 64-bit integer minor units. No floating-point money, automatic decimal conversion, rounding, or FX is performed. Both accounts must have the same currency. Fractional JSON amounts and arithmetic overflow are rejected.

One PostgreSQL transaction writes the transaction header, debit and credit postings, balances, outbox event, and audit record. The default strategy locks the two balance rows in deterministic UUID order under READ COMMITTED. Optimistic and SERIALIZABLE alternatives are experimental comparisons with bounded retries. The deliberately unsafe variant 0 is blocked through the public API and as the default; internal experiments retain it as a negative control.

Transfers require `Idempotency-Key` (1â€“128 non-control characters). Keys are scoped to the authenticated subject and bound to a SHA-256 hash of the normalized request. A committed duplicate returns its existing transaction; a conflicting body returns 422. Retry an ambiguous timeout using the **same key and body**. Keys are retained without TTL. Account creation has no idempotency contract.

## Run locally

Prerequisites: Docker Desktop with its Linux engine available. The demonstration uses PostgreSQL 16 and Kafka. Published Compose ports bind to localhost.

```powershell
powershell -ExecutionPolicy Bypass -File scripts/local-demo.ps1
```

The script generates a random signing secret and short-lived local tokens, creates accounts, performs funding and customer transfers, checks replay and projection results, and writes a git-ignored summary containing tokens. Cold image builds can take longer than five minutes. The evaluator and post-load reused-stack check were verified locally on 2026-10-24; see the evidence boundaries below.

For manual Compose use, set `JWT_SECRET` to at least 32 random bytes and run `docker compose -f deploy/compose.yaml up --build -d`. Both services are HS256 resource servers; an external trusted issuer must supply `sub`, `exp`, and `roles`. Customer projection access additionally requires signed `accounts` claims. This repository does not provide login, password storage, refresh tokens, or revocation.

## Test

```powershell
.\mvnw.cmd clean verify -Pcoverage --batch-mode
```

Integration, concurrency, property, and Kafka failure tests require real Docker infrastructure. Unavailable infrastructure is a failed/unverified run, never a pass. CI runs the suite, builds images, emits SBOMs, and blocks actionable HIGH/CRITICAL Trivy findings while preserving raw scan results. Spring Boot 4.0.9 manages patched Spring Framework 7.0.10; no active vulnerability applicability exceptions remain. [Migration checks](https://github.com/pavann19/LedgerLine/actions/runs/40176097472) passed 55 Java tests, five policy tests, and both image gates. See [evidence boundaries](docs/EVIDENCE.md) for runtime results and historical release scope.

## Documentation

The temporary Boot 4 Azure validation on 2026-10-25 passed 51,744 load transfers with zero HTTP failures across the existing 20/100-VU profiles. The 100-VU profile measured 213 transfers/s and 546 ms p95; recovery, duplicate-event and 103,578-posting backup/restore checks passed. Resources were deleted. See [measured cloud results and limits](docs/AZURE_BOOT4_VALIDATION.md); a continuously hosted demo is not provided.

The transfer-query optimization removes four PostgreSQL statements per fresh transfer using the safe strategies and passes 59 regression tests. Its repeated Azure performance validation failed and did not complete (latency thresholds and projection catch-up); no stable percentage gain is claimed. See [optimization results and retained limits](docs/TRANSFER_QUERY_OPTIMIZATION.md).

- [Five-minute portfolio walkthrough](docs/PORTFOLIO_WALKTHROUGH.md)
- [Architecture](docs/ARCHITECTURE.md)
- [Security boundaries](docs/SECURITY.md)
- [Financial invariants](docs/FINANCIAL_INVARIANTS.md)
- [API usage](docs/api-examples.md)
- [Database and deployment](docs/DATABASE_DEPLOYMENT.md)
- [Testing and failure matrix](docs/TESTING.md)
- [Evidence boundaries](docs/EVIDENCE.md)
- [Image vulnerability applicability](security/README.md)
- [Isolation experiment method](docs/experiments/01-isolation.md)

The database user in local Compose has administrative privileges. Direct SQL can alter balances without postings, append to old transactions, disable triggers, or truncate history. SQL administrators and Kafka publishers are trusted. Projection data is eventually consistent and is never the balance authority. No guarantee of exactly-once end-to-end processing, uninterrupted delivery, deadlock elimination across all strategies, or safe internet deployment is made.
