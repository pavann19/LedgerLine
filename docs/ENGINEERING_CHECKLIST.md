# Backend engineering checklist

Assessment: 3 November 2026. Six of eight tasks are complete within the tested
application scope. Two remain partial. This checklist does not certify production
banking, every possible failure mode, host availability or a completed endurance run.

| Task | Status | Evidence and limits |
|---|---|---|
| Unit and integration tests | Complete, bounded scope | Java unit tests plus disposable PostgreSQL/Kafka integration tests cover money arithmetic, authorization, metadata races, constraints, recovery and projection ordering. See [testing](TESTING.md). |
| Duplicates and idempotency | Complete | Same/conflicting/principal-scoped keys, concurrent duplicates, exhausted-funds replay and projection event deduplication are tested. |
| Rollback and failure recovery | Complete, tested scenarios | PostgreSQL timeout/deadlock rollback, coupled journal/balance rollback, Kafka interruption and durable quarantine have executable regressions. Additional finite lab recovery is documented separately. |
| Concurrency and consistency | Complete, declared guarantees | Pessimistic locking, optimistic version checks, bounded serialization retries, zero-sum constraints and full-history projection oracles are tested. Projections are eventual; arbitrary mixed-strategy execution is not proven deadlock-free. |
| Structured logging and distributed tracing | Partial | Application-owned correlation IDs propagate through HTTP, outbox payloads and projection records. Structured JSON logging, propagated trace/span context and an end-to-end collector/exporter verification are not implemented as a complete tracing pipeline. |
| Migrations and schema versions | Complete | Flyway versioned SQL migrations accompany the services and run in integration tests. Applied migrations are immutable; this change adds no migration. |
| GitHub Actions build, tests and static analysis | Partial | Hosted Java build/invariant tests, worker tests, secret scanning and image/SBOM vulnerability checks exist. A dedicated Java code static-analysis gate is still missing. Exact revisions require their own CI results. |
| Architecture decisions and failures | Complete | [Architecture](ARCHITECTURE.md), [financial invariants](FINANCIAL_INVARIANTS.md), [failure matrix](TESTING.md) and [recovery procedures](RECOVERY_AND_RECONCILIATION.md) describe choices, tradeoffs and limits. |

## Changes in this update

The pessimistic transfer path avoids speculative account reads and a redundant
idempotency lookup, and persists journal/outbox/audit plus cached balances in one
database command. Authorization, locked metadata checks, transaction-scoped key
serialization, checked arithmetic and database constraints remain authoritative.
The rollback regression covers both journal-only and combined balance writes.

Transient database/connectivity failures and transaction timeouts return a generic
503 problem response without connection details. Its text preserves uncertainty
and tells clients to retry transfers with the same principal, key and request body.
It does not automatically retry or claim that an unknown outcome rolled back.

No isolated performance improvement is demonstrated by these source changes.

## Performance and operational evidence

The [sanitized measurement summary](evidence/backend-hardening-2026-11-03.json)
retains all five failed local attempts. They were measured on the earlier working
candidate, before reconciliation with current main; they do not benchmark this
publication commit. The workload and original gates were unchanged. CPU/pool
variants and source changes prevent a code-only comparison.

The latest measured original workload completed 20,399 transfers with zero HTTP
failures, p95 833.09 ms and p99 1388.55 ms. **p95 fails the unchanged <750 ms
gate**. The separate many-account pass cannot replace it. Final reconciliation
matched 64,914 ledger/projection postings with no drift, quarantine, outbox backlog
or known Kafka lag. Finite service/Kafka recovery, unread-reply same-key replay,
post-commit process termination/replay, 11-table restore and fixture cleanup passed.
Raw private receipts remain retained; the summary is not a substitute for them.

The prior working candidate passed 76 Java tests and nine worker tests. The
publication candidate is based on newer main and must pass its larger suite and
its own hosted checks; the old test total is not its test count. The reconciled publication source subsequently passed all 108 Java tests with zero failures, errors or skips, plus nine worker tests. Hosted results must be checked for the published commit. Surefire's forced
test-JVM exit warning remains unresolved despite passing assertions. No real
24-hour soak or production-readiness claim is made.

## Reproduction and remaining work

Use Java 21 and Docker with fresh synthetic Testcontainers:

```sh
./mvnw clean verify -Pcoverage --batch-mode
python3 -B -m unittest discover -s reconciliation-agent -v
```

For the original performance profile, use the documented isolated deployment and
`bench/k6-cloud-load.js` with `K6_PROFILE=performance`; retain resource settings,
successful response counts, convergence and complete financial oracles. Do not
point a benchmark at existing financial data or infer capacity from test counts.

Remaining work: structured log/trace implementation and collector outage tests;
a dedicated static-analysis CI gate; attribution of pool/lock/commit latency on a
controlled host; resolution of the test shutdown warning; and the original load
gate before any explicitly authorized 24-hour run.
