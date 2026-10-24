# Testing

## Commands

Full build and all executable invariants (Java 21 target, Docker required):

```powershell
.\mvnw.cmd clean verify -Pcoverage --batch-mode
```

Focused unit regressions (does not prove database atomicity):

```powershell
.\mvnw.cmd clean verify -Pcoverage --batch-mode '-Dtest=ReleaseBoundaryTest,PrincipalScopeTest,RequestHasherTest,JwtBoundaryTest,TransferStrategyRegressionTest,MoneyJsonTest' '-Dsurefire.failIfNoSpecifiedTests=false'
```

Live HTTP/outbox/Kafka/projection evaluator:

```powershell
powershell -ExecutionPolicy Bypass -File scripts/local-demo.ps1
```

## Coverage meaning

DatabaseConstraintInvariantTest checks deferred zero-sum, nonnegative protected accounts, overdraft permission, and immutable postings. FinancialEdgeRegressionTest checks all safe strategies at BIGINT overflow/underflow, rollback, historical header/audit mutation, type mismatch, concurrent debit, and exhausted-funds duplicate replay. IdempotencyIntegrationTest checks identical/conflicting/principal-scoped/concurrent keys. ConcurrentTransferStressTest checks a 50-thread, 1000-attempt workload. IsolationExperimentTest retains its intentionally broken negative control. DoubleEntryPropertyTest uses 20 generated traces (not 1000) against PostgreSQL; it does not generate every failure or amount boundary. AsyncFailureAndResilienceTest uses real Kafka pause/recovery and a simulated post-send hook. ProjectionDeduplicationTest checks PostgreSQL event deduplication and event-time ordering; it does not prove every consumer failure path.

MoneyJsonTest, ReleaseBoundaryTest, PrincipalScopeTest, JwtBoundaryTest, and TransferStrategyRegressionTest are focused unit tests. Mocked rollback intent is not proof of PostgreSQL rollback; keep the database regressions and end-to-end evaluator.

## Failure matrix

| Failure | Expected implemented behavior | Evidence/limitation |
|---|---|---|
| Invalid amount/body | Reject before financial writes | JSON/core validation; malformed API checks |
| Foreign source/read | Forbidden, no money movement | PrincipalScopeTest and API smoke |
| Database unavailable | Fail/rollback; retry ambiguous commit with same key | Connection-loss injection UNVERIFIED |
| Transaction timeout | Rollback before commit; ambiguity if response lost | Dedicated timeout injection UNVERIFIED |
| Deadlock | DB aborts victim; default API may return error; SSI bounded retry | Dedicated deterministic deadlock UNVERIFIED |
| Serialization conflict | Retry entire transaction at most five times | IsolationExperimentTest; exhaustion can fail |
| Duplicate same key/body | Original transaction, one mutation | Idempotency and exhausted-funds regressions |
| Same key/different body | Conflict, no new transfer | IdempotencyIntegrationTest |
| Client timeout after commit | Same key/body replays stored result | Lost-response simulation uses replay, not killed process |
| Process crash before commit | PostgreSQL should abort uncommitted writes | Real process kill UNVERIFIED |
| Process crash after commit | Retry same key recovers committed result | Real process kill UNVERIFIED |
| Kafka unavailable | Transfer DB commit retained, outbox retries | Real Kafka pause/recovery test |
| Send succeeded, publication marker absent | Potential redelivery; consumer event-ID dedup | Simulated relay hook and separate projection test |
| Malformed request | 400 with generic problem detail | DTO/JSON and handler checks |
| Unexpected DB state | Constraints reject supported metadata violations | DB constraints; no universal repair mechanism |
| Duplicate transaction ID/reference | UUID/unique key constraints reject | Duplicate client keys tested; no separate reference feature |
| Stale version | Optimistic write rejects/retries | IsolationExperimentTest |
| Concurrent debit | Protected balance cannot overdraw | Barrier-based database regression and stress |
| Closure race | No closure API; metadata locks protect SQL status changes during transaction | FinancialEdgeRegressionTest coordinates an uncommitted closure and observes pg_blocking_pids before releasing the writer |
| Poison Kafka record | Spring Kafka default recovery may stop retries and skip a record | Poison-message/reconciliation behavior UNVERIFIED |

No mock, infrastructure startup failure, old artifact, or skipped job counts as PASS. CI only runs on main/master push or pull requests targeting them. No commit/push occurred in this audit, so new remote CI is unverified. Image scan gate needs exact freshly built images; archived reports are not current results.

The unchanged k6 correctness profile exercises synchronous transfer responses; it does not verify projection catch-up. Running the evaluator on an existing database/broker with a large backlog can exceed its 60-second statement wait. Its failure is not proof of a failed financial commit and must not be presented as a passing evaluator run. Historical seeded/broken-variant database volumes are not clean financial baselines; preserve them and use a separate fresh environment for clean evidence.


## Stability follow-up verification

`ProjectionDeduplicationTest` additionally compares incremental balances with a full-history SQL oracle across late, cross-day, and equal-timestamp events. It checks persisted UTC timestamp rounding and uses PostgreSQL xmin to prove that in-order events do not rewrite earlier history. The original implementation fails the latter negative control. Database tests are required; compile-only checks do not establish correctness.

Both ViewRenderingBoundaryTest classes inspect registered MVC application handlers and prohibit XSLT resolvers/views and explicit view return types. Unsafe view and SSE fixtures are negative controls. `python scripts/test_image_scan_policy.py` checks fail-closed applicability evaluation. See security/README.md for the exact scope and expiry.

To exercise the unchanged k6 workload, require projection catch-up, and repeat the demo on the same populated broker/database without deleting data:

```powershell
powershell -ExecutionPolicy Bypass -File scripts/verify-local-stability.ps1
```

The script retains logs outside the checkout, runs the existing correctness workload unmodified, requires zero balance drift and the expected scoped total, waits at most 60 seconds for projection row catch-up, then reruns the evaluator and API probes against the same stack. It must be executed successfully before reused-stack stability is claimed. On failed/rekeyed demo runs, the older local summary is moved to an ignored previous-summary file so stale tokens/results are not mistaken for current evidence.
