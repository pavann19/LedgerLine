# Matched local performance experiment

Protocol declared before the first instrumented load run on 3 November 2026.
Results are pending. This is a bounded synthetic local experiment, not a release,
production capacity claim or an endurance test.

## Fixed experiment

- Instrumented baseline and candidate contain identical phase timers, JSON logs,
  tracing, profiler settings and observer sampling. Select the implementation
  change only after reading the baseline profile.
- Run order: baseline 1, candidate 1, candidate 2, baseline 2. Each run uses a new
  Compose project, fresh PostgreSQL/Kafka storage and the same synthetic fixture
  creation process. Original workload randomness is retained, so the request
  traces and generated UUIDs are not identical.
- Keep the original 150-second two-account performance workload and its response
  counting unchanged. Run the separately labelled many-account scenario on the
  final candidate; it cannot substitute for original-workload acceptance.
- Limits: ledger 2 CPUs / 768 MiB, PostgreSQL 2 CPUs / 600 MiB, Kafka 1 CPU /
  700 MiB, projection 0.75 CPU / 640 MiB, ledger Hikari maximum 4; load generator
  0.5 CPU / 256 MiB. Use the same digest-pinned images and application JVM settings.
- PostgreSQL fsync, synchronous commit and full-page writes remain enabled.
  Keep applied migrations and deterministic accounting assertions unchanged.
- Do not run competing project builds or synthetic tests during a load leg.
  Existing unrelated host services remain untouched and are recorded. Matched
  container limits do not create a dedicated physical host.

## Required observations and acceptance

For every leg, retain source/artifact/workload/Compose/image identities, complete
failed attempts, k6 p50/p95/p99 and request/error counts, PostgreSQL query timing
and wait samples, Hikari acquisition/pending/active counts, JVM CPU/thread/GC
profiles, cgroup CPU throttle/memory observations, Kafka committed-offset lag,
financial convergence and scoped cleanup evidence. Missing required profiling
data is UNVERIFIED, never zero or a completed profiling task.

Original gates remain p95 <750 ms, p99 <1500 ms, HTTP failure fraction <1% and
convergence within 180 seconds, with no monetary/projection invariant failures.
For this experiment's improvement claim, both candidate runs must pass those
gates and demonstrate at least 10% higher median throughput and 10% lower median
p95 than the two baseline runs, with the phase/query evidence supporting the
specific changed mechanism. These additional comparison criteria do not weaken
the original gates. Two repeats support a local observed effect, not population
confidence, production availability or a universal speedup.

If conditions, profiler data or outcomes fail these requirements, report the
failure and investigate it; do not select only the best run or change thresholds
after seeing the results. No actual 24-hour run is started by this protocol.
