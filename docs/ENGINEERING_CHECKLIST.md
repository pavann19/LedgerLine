# Backend engineering checklist

Assessment: 3 November 2026. All eight backend tasks and seven performance-investigation tasks are implemented and locally verified within the scope below. The published revision must also pass its own [hosted CI](https://github.com/pavann19/LedgerLine/actions/workflows/ci.yml?query=branch%3Abackend-hardening-20261103). This is a bounded engineering case study, not certification of production financial operations, HA or endurance.

| Backend task | Status | Evidence and limits |
|---|---|---|
| Unit and integration tests | Complete, declared scope | 121 Java tests pass with real disposable PostgreSQL/Kafka; nine worker tests pass. Money arithmetic, authorization, constraints, metadata races, projection ordering and failure boundaries are covered. [Testing](TESTING.md) names finite coverage. |
| Duplicate transactions and idempotency | Complete | Same/conflicting/principal-scoped keys, concurrent duplicates, exhausted-funds replay and event deduplication are tested. Valid identical replay preserves one monetary mutation. |
| Rollback and failure recovery | Complete, tested scenarios | Database timeout/deadlock, coupled journal/balance rollback, Kafka interruption and durable quarantine regressions pass. Historical finite recovery and restore evidence is retained separately. |
| Concurrency and consistency | Complete, declared guarantees | Advisory key serialization, deterministic balance locks, checked arithmetic, optimistic versions, bounded serialization retries and database invariants remain authoritative. Projections are eventual. |
| Structured logging and distributed tracing | Complete | ECS JSON logs and bounded OTLP export span HTTP, persisted outbox, Kafka and projection. Actual exported cross-service parents and a stopped-collector commit/replay/convergence smoke pass. [Tracing](TRACING.md), [smoke](TRACE_FLOW_SMOKE.md). |
| Migrations and schema versions | Complete | Flyway versioned migrations run in the integration suite. Applied migrations remain immutable; this update needs no schema migration. |
| GitHub Actions build, tests and static analysis | Complete implementation and local controls; exact-revision hosted gate required | CI includes Java build/integration/coverage, dedicated pinned SpotBugs correctness/concurrency analysis, valid/known-defect controls, secret and image/SBOM checks. [Static analysis](STATIC_ANALYSIS.md). |
| Architecture decisions and failure scenarios | Complete | [Architecture](ARCHITECTURE.md), financial invariants, failure matrix and recovery procedures explain transaction authority, eventual delivery, telemetry loss and replay tradeoffs. |

## Performance investigation

| Performance task | Status | Evidence |
|---|---|---|
| Reproducible local load tests | Complete | Fresh isolated projects and volumes; four identified original-workload legs plus a separate many-account run. |
| CPU, memory, database connections and thread pools | Complete | Both-service JFR CPU samples, cgroup CPU/throttling/memory, JVM/GC, Hikari, Tomcat and database observations pass mandatory profile checks in every matched leg. |
| Lock contention and transaction bottlenecks | Complete, measured local attribution | Phase timers, pool waiting, PostgreSQL waits and queries identify connection acquisition as a major mean-latency source. Balance serialization remains material; generic parked stacks are not treated as causal proof. |
| PostgreSQL query latency and Kafka lag | Complete | Real server call/time statistics and committed-offset lag samples; final financial convergence and zero known lag. Query percentiles are not inferred from aggregate timing. |
| Baseline throughput, p50/p95/p99 and errors | Complete | Both baselines and both candidates retained, including failing B1. Zero HTTP errors in all four. |
| Largest measured bottleneck and before/after | Complete, bounded local result | Removing the speculative replay miss cuts replay queries two to one and acquisitions about three to two per new transfer. Both candidates pass original gates; declared median criteria pass. |
| Benchmark scripts and environment | Complete | [Portable lab](PERFORMANCE_BENCHMARK.md), digest-pinned inputs, source/JAR manifests, immutable original workload and a verified baseline-restoration patch. |

The [full report](PERFORMANCE_RESULTS_2026-11-03.md) and [sanitized receipts](evidence/performance-investigation-2026-11-03.json) show median throughput +15.56% and median p95 -20.20%. The repeat baseline improved about 31% over the first baseline, and the second paired throughput difference is only 5.06%. Shared-host variability prevents assigning the entire median difference to code or generalizing a production speedup. Query/check-out reduction is directly observed; remaining waiting is documented.

## Verification and operational limits

Use Java 21 and Docker for fresh integration fixtures:

```sh
./mvnw clean verify -Pcoverage --batch-mode
./mvnw -Pstatic-analysis -Dmaven.test.skip=true verify --batch-mode
python3 scripts/test_static_analysis_gate.py
python3 scripts/test_benchmark_controls.py
python3 scripts/test_trace_smoke_controls.py
python3 -B -m unittest discover -s reconciliation-agent -v
```

The benchmark guide gives isolated build/freeze/run commands, declared resource settings and a baseline patch verified against 93 production files. Raw recordings, credentials, environment inspection and logs remain private; reviewed metrics and source/artifact hashes are public. Earlier failed experiments remain in the [historical summary](evidence/backend-hardening-2026-11-03.json).

Completed replays now use a transaction connection and advisory lock; replay-heavy latency remains unmeasured. Malformed changed bodies can fail shape validation before payload-conflict resolution. Authorization still precedes strategy execution. No durability, accounting assertion or original load gate was weakened.

The earlier forced test-JVM exit warning was not observed in the latest complete integration run; this does not establish universal shutdown behavior. A real 24-hour soak, production operation, HA and cloud deployment remain UNVERIFIED or outside this task. No shortened load run substitutes for endurance acceptance.
