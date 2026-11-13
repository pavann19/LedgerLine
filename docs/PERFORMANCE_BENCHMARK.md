# Reproducible local performance diagnostics

This lab creates a random Compose project, fresh PostgreSQL/Kafka volumes and twenty funded synthetic accounts. APIs bind to ephemeral loopback ports. It never connects to an existing database, cluster or named application volume. It is a bounded diagnostic run, not recovery, restore, soak or production acceptance.

Requirements: Python 3.10+, Docker Engine with Compose, Java 21 and Maven for producing the application JARs. Public runtime inputs are pinned by registry digest in `deploy/benchmark-images.json`; missing inputs are pulled explicitly. The lab records the resolved image IDs and source/artifact hashes. Registry availability and enough local disk/memory are prerequisites. No private application image is required.

## Build, freeze and run

Use two separate source directories for an instrumented baseline and an optimized candidate. Build and test each with `./mvnw --batch-mode verify` (Windows: `.\mvnw.cmd --batch-mode verify`). Preserve the test output. Instrumentation must be present in both versions before changing the transaction implementation. Do not edit either directory after freezing it.

From this checkout, run:

```text
python scripts/benchmark-manifest.py --workspace /path/to/baseline --output /path/to/baseline-identity.json
python scripts/benchmark-manifest.py --workspace /path/to/candidate --output /path/to/candidate-identity.json
python scripts/benchmark-lab.py --candidate /path/to/baseline-identity.json --evidence /path/to/run-baseline-1 --scenario original
python scripts/benchmark-lab.py --candidate /path/to/candidate-identity.json --evidence /path/to/run-candidate-1 --scenario original
python scripts/benchmark-compare.py --baseline /path/to/run-baseline-1 --candidate /path/to/run-candidate-1 --output /path/to/comparison-1.json
```

Every output path must be new. The manifest helper records hashes; it cannot independently certify that a JAR was built from the source. For an isolated copied build without Git, pass `--build-receipt /path/to/build/identity.json`: every copied-source hash is verified, the original head is retained, and an adjacent summary is linked by hash and status when present. Preserve independent build/test receipts and verify they identify the same artifact hashes. The harness refuses changed sources or artifact hashes. Do not invoke two performance labs concurrently. Run correctness/static checks in parallel before experiments, then stop competing synthetic fixtures. Record unavoidable unrelated host activity; never stop shared services to improve a score.

Defaults: ledger CPU 1, PostgreSQL CPU 1, Kafka CPU 0.5, projection CPU 0.75; memory caps 768/600/700/640 MiB respectively; ledger Hikari maximum 12. Load generator CPU 0.5 and memory 256 MiB. Explicit CPU/pool flags are recorded in the identity. All comparison legs must use identical settings. PostgreSQL fsync, synchronous commit and full-page writes must remain on. Each leg gets fresh storage. The harness retains actual host/daemon configuration and container limits so resource differences are reviewable.

`--scenario original` runs the unchanged original `bench/k6-cloud-load.js` performance profile with its original p95 <750 ms, p99 <1500 ms and error-rate <1% gates. `--scenario many` runs the separately labelled twenty-account scenario; `both` runs original followed by many. Many-account success never substitutes for the original gate. Reconciliation enforces the original 180-second convergence limit and monetary invariants. Failed thresholds and failed attempts remain failed.

Repeat alternating order (baseline/candidate, then candidate/baseline) on the same host before generalizing. A single pair establishes an observed local difference, not statistical certainty. The original workload intentionally uses randomized transactions; matching script/configuration is not an identical request trace. Report per-run distributions and host conditions, not only the best run. The comparison helper rejects mismatched workload, resource, harness, image or Compose identities; source/artifact identities may differ as intended.

## Evidence

- k6 raw summaries/logs: throughput, p50/p95/p99, errors and thresholds.
- `profiles.jsonl`: database connection/active/blocked counts and waits, real Kafka consumer-group CURRENT-OFFSET/LOG-END-OFFSET/LAG output, Hikari acquisition/pending/active/idle, Tomcat/JVM threads and memory, GC, phase timer count/sum/max. Missing metrics are explicitly UNVERIFIED, not zero.
- `*-profile-start.json`, `*-profile-end.json` and `*-timer-deltas.json`: explicit boundaries around k6, before reconciliation. Phase/Hikari means use cumulative count/sum deltas; timer percentiles are never summed. Total transaction excludes authentication and initial replay lookup; commit cleanup includes deferred checks/commit/release; balance-lock-to-completion approximates the client boundary, not exact server lock ownership. Observer database queries and metrics requests contribute to diagnostics/resource counters.
- `*-queries.json`: PostgreSQL `pg_stat_statements` query calls, actual server execution time (milliseconds), min/max/stddev, cache/block/IO timings. Stats reset immediately before each scenario. These aggregate query times include server execution/lock waits but exclude client network and pool acquisition; they are not a query p95 or full transaction latency.
- `*-cpu-deltas.json`: cgroup v1 or v2 CPU usage/throttle counters before/after; memory current/events snapshots. V1 nanosecond CPU usage/throttled time is normalized to microseconds, matching v2 units. `resources.jsonl` retains Docker samples. Unavailable counters remain UNVERIFIED. V1 memory failcnt and v2 memory events retain their original meanings.
- `*.jfr`, JFR summaries, parsed CPU/monitor/socket event counts and top sampled frames, and printed execution/monitor/socket/parking samples: bounded 64 MiB profile recordings for both services. Export streams base64 through Docker exec, avoiding Docker cp visibility failures for tmpfs. If this runtime rejects the JDK optimized dump copy, the harness validates completed repository chunks with `jfr summary`, concatenates them using ordinary reads, validates JFR magic/size and preserves the dump warning/export method. Empty recordings never pass. `profiles.jsonl` includes ledger thread dumps. Sampling is approximate; stack samples do not alone prove latency causality. Unavailable JFR is UNVERIFIED.
- `*-host.json`: daemon/host information, effective container limits and durability settings. Diagnostics run identically on both legs and have observer overhead. `*-profiling-status.json` validates required phase metrics, pools/threads/resources, real offset-lag samples, nonempty server query timings and successful JFR CPU samples. Missing mandatory observations make profiling UNVERIFIED independently of the k6 gate result. A profiler result cannot be called complete if required observations are UNVERIFIED.
- `cleanup.json`, `result.json`, `SHA256.json`: scoped cleanup, original load/convergence outcome and evidence integrity. Cleanup errors are retained and fail the run rather than masking a result.

Raw receipts include generated fixture tokens, credentials, synthetic SQL and container environment. Keep them private. Publish a sanitized performance report with source/artifact/image/workload identities, resource settings, failed controls, query/phase/CPU evidence and limitations. Remove only synthetic secrets, not negative results. The harness does not publish files itself.

## Root-cause interpretation

Correlate phase timer deltas with database waits, query execution time, pool acquisition, cgroup throttle deltas and JFR stacks. For example, large balance-lock time with blocked PostgreSQL sessions supports account serialization; a high HTTP p95 alone does not. Compare one implementation change at fixed resources/durability/workload. Report the mechanism, before/after distributions and correctness/convergence outcomes. If CPU throttling or host contention dominates, report that limitation instead of claiming a code-only improvement. Recovery and restore require the separate recovery acceptance workflow; this lab never upgrades their status.

Local helper checks (no services launched):

```text
python scripts/test_benchmark_controls.py
python scripts/benchmark-lab.py --help
```
## Reconstruct the measured production baseline

The public candidate includes `bench/baselines/restore-speculative-replay.patch` so the measured baseline can be rebuilt without a private source snapshot. Start from the exact candidate revision identified in the performance report. In a separate detached worktree, apply the patch and build normally:

```text
git worktree add --detach ../ledgerline-baseline REVISION_FROM_REPORT
git -C ../ledgerline-baseline apply --check bench/baselines/restore-speculative-replay.patch
git -C ../ledgerline-baseline apply bench/baselines/restore-speculative-replay.patch
```

Replace `REVISION_FROM_REPORT` with the report's candidate revision. Run `./mvnw --batch-mode verify` from `../ledgerline-baseline` (Windows: `.\mvnw.cmd --batch-mode verify`), preserving its output. Return to the candidate checkout, then freeze the rebuilt baseline and run the experiment using the same harness and explicit limits as the candidate:

```text
python scripts/benchmark-manifest.py --workspace ../ledgerline-baseline --output /path/to/rebuilt-baseline-identity.json
python scripts/benchmark-lab.py --candidate /path/to/rebuilt-baseline-identity.json --evidence /path/to/rebuilt-baseline-run --scenario original --ledger-cpus 2 --postgres-cpus 2 --kafka-cpus 1 --ledger-pool-size 4
```

The patch restores only the speculative replay lookup and its matching strategy regression expectations. Instrumentation, tracing, financial controls, migrations, resources and the original workload remain equivalent. Patch SHA-256: `2def8ec6693263cdb89888884fc522740651363a4b6fc7715d34beb46df0e64b`.

Applying this patch to the reviewed candidate was checked against the frozen instrumented baseline: all **93 production source/resource and Maven POM files matched byte for byte**, with identical file sets. This is production-source reconstruction, not a claim that all historical test files or rebuilt JAR bytes are identical. Two later test differences remain: `AsyncFailureAndResilienceTest` adds a real HTTP/outbox/Kafka trace assertion, and `ConsumerTracingBoundaryTest` is an additional consumer-boundary test. They do not enter production JARs. Retain the rebuilt source/artifact manifest and fresh test results; the Git head alone does not describe the patched worktree. Rebuild both sides locally rather than treating historical JAR hashes as a reproducible-build guarantee.


Published evidence retains measured-workspace SHA-256 values and LF-normalized source hashes. Git can normalize text line endings during checkout; compare the LF-normalized hashes for a fresh checkout rather than treating a CRLF/LF difference as a runtime source change. JAR hashes identify the measured artifacts; these instructions do not promise byte-identical rebuilt JARs.


The evidence JSON records a post-experiment whitespace-only publication cleanup for the benchmark helpers and one test EOF. Python AST equality was checked; runtime source and measured receipts were unchanged. Use its measured/publication hash mapping when comparing the published helper with the retained experiment identity.
