# Portfolio walkthrough

LedgerLine demonstrates transactional backend correctness and reliable asynchronous projections with Java 21, Spring Boot, PostgreSQL and Kafka.

## What to inspect in five minutes

1. Read the [architecture diagram](ARCHITECTURE.md) and trace the atomic ledger transaction through the outbox to the eventual statement.
2. Open the [financial invariants](FINANCIAL_INVARIANTS.md): integer money, balanced postings, ownership and principal-scoped idempotency are executable contracts.
3. Inspect `FinancialEdgeRegressionTest`, `TransferStrategyRegressionTest` and `ProjectionDeduplicationTest`. The projection tests compare against full-history SQL and verify late events, duplicate delivery and overflow rollback.
4. Inspect the [published test and image evidence](EVIDENCE.md#published-ci-evidence). Distinguish remote CI from local load checks.
5. Read the [security boundaries](SECURITY.md) and [exact vulnerability decisions](../security/README.md), including their expiry.

## Demonstrate locally

With Docker Desktop's Linux engine running, execute from the repository root:

```powershell
powershell -ExecutionPolicy Bypass -File scripts/local-demo.ps1
```

Allow time for cold builds. The evaluator creates balanced funding, submits a customer transfer, replays its original key, and checks balances and eventual projection results. It fails if those assertions fail. Its ignored summary contains short-lived local tokens; keep it private. Use [API examples](api-examples.md) for the request contracts.

For the longer correctness demonstration, run:

```powershell
powershell -ExecutionPolicy Bypass -File scripts/verify-local-stability.ps1
```

This runs the unchanged load workload, compares statements and daily summaries against SQL history, and reruns the evaluator on the populated stack. It requires more resources than the short demo.

## Interview discussion

- Explain why PostgreSQL commit and Kafka publication cannot be one local transaction, and how the outbox permits redelivery.
- Explain why event deduplication and principal-scoped HTTP idempotency solve different problems.
- Compare the default deterministic balance locks with the experimental optimistic and serializable strategies.
- Show why late events require suffix repair and why statements cannot authorize spending.
- Discuss the remaining operational gaps: TLS, least-privilege database roles, backup recovery, authenticated messaging and cloud verification.

The demonstrated scope is a portfolio backend. Production banking operation, high availability and universal capacity remain unverified. A hosted demo should only be advertised after deployment and live checks succeed.
