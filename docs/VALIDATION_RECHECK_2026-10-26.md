# Azure claim revalidation — 26 October 2026

This report distinguishes fresh execution from the historical 25 October result. It does not establish production capacity, availability, or disaster-recovery RTO/RPO.

## Provenance

- Working branch at start: `transfer-query-optimization`, HEAD `f94ed87`. Application files on that branch were not used for the original-workload rerun.
- Isolated checkout: private local evidence (excluded from Git), original image-build snapshot `7e68624bb3a683e261152142a7b3830c7c2a94fe` (application source `02ae50196ea21256bae1173fbd65494d5dc7167e`).
- Private fresh receipts: private local evidence (excluded from Git).
- Original private receipts: private local evidence (excluded from Git).
- All nine original receipts match the SHA-256 values in `evidence/azure-boot4-2026-10-25.json`, including the backup, k6 summaries/logs, report, final assertions and resource samples.
- Windows checkout line endings prevented the first image build. No load test ran in that attempt; resource group absence was confirmed before retrying. The isolated checkout was restored with LF line endings; Git content comparison against the snapshot passed. The historical probe hash is reproduced by CRLF encoding the same probe; the rerun uses LF. The workload hash matches exactly.

## Historical measurement audit

The original performance summary and log agree: 32,018 successful transfers, 32,018 HTTP requests, zero HTTP failures, 213.44037322960205 successful transfers/s, and HTTP request p95 545.56289645 ms. `32,018 / 150` is 213.4533; the small difference reflects k6's measured elapsed interval (approximately 150.009 seconds), rather than division by the nominal stage duration. Rounded resume figures of 213/s and 546 ms agree with these receipts.

The workload records successful transfers only for HTTP 201. HTTP 200 replay responses are counted separately. The p95 is HTTP request duration, excluding the per-iteration 20 ms sleep; it is not iteration duration. Individual request samples were not retained, so an independent reconstruction of the original percentile from raw timings is unavailable. The claimed value is verified against the original k6 summary and log.

The original recovery report records 20 transfers during a stopped projection consumer and another 20 during a stopped Kafka broker. Restart-to-observed-convergence times were 20.422 and 8.892 seconds, respectively. These are separate outages and polling-based observations. The 8.980-second ledger restart/replay is a distinct check.

## Fresh restoration of the retained dataset — PASS (local)

The hash-verified original backup was restored using PostgreSQL 16 in an isolated container without published ports or network access. It contains exactly 103,578 ledger postings and 103,578 projection rows. A new dump of that restored database was restored into a second database; all 11 count/content fingerprints match. Both databases passed the full original monetary and projection SQL assertions, including zero global posting sum, negative customer balances, cached balance mismatches, unbalanced transactions, statement drift and daily-summary drift. No unpublished outbox events remained. The container and its data volume were removed afterward.

This is fresh local restoration and roundtrip evidence. The original live Azure source no longer exists, and its individual fingerprints were not retained. It does not independently recompare against that deleted source. The historical report records the original Azure comparison as passing all 11 tables.

Private outputs: `retained-restore-result.json`, `restored-full-invariants.txt`, `roundtrip-full-invariants.txt`, and `receipt-hash-check.json` in the fresh-receipt directory.

## Fresh Azure execution — FAIL

Authorized configuration: Central India, `Standard_D4ls_v6`, managed PostgreSQL `Standard_D2ds_v5` General Purpose with 32 GiB P4 storage / 120 IOPS, ledger 1.75 CPUs / 2 GiB, projection 0.75 CPUs / 1,400 MiB, original workload and on-host k6. Azure's reported database configuration matches these settings. The k6 image digest matches the original run. Application images were rebuilt from the original snapshot; image digests differ and are retained privately. The original snapshot launcher keeps an SSH session open while executing on-host; latency measurement is entirely on-host.

Both services passed health checks, all 22 HTTP acceptance checks passed, and baseline SQL reconciliation passed.

| Profile | Transfers | Transfers/s | HTTP failures | p95 | p99 | Result |
|---|---:|---:|---:|---:|---:|---|
| Correctness, 100 seconds | 6,568 | 65.65 | 0 | 499.20 ms | 1,000.67 ms | PASS; post-load reconciliation passed |
| Performance, 150 seconds | 9,575 | 63.83 | 0 | 2,223.14 ms | 3,987.25 ms | FAIL; k6 exit 99 |

The original performance gates are p95 <750 ms and p99 <1,500 ms. Both failed. The original 180-second post-load projection catch-up deadline also failed. A diagnostic observation after load showed 32,294 ledger rows, 29,216 projection rows and zero unpublished outbox events; it is not a final reconciliation result. The probe stopped at `wait_for(settled)` before injecting outages. Receipt collection completed before automatic resource-group deletion began.

Fresh cloud outage injection, restart replay, duplicate Kafka consumption, cloud backup/restore and final post-failure reconciliation were **SKIPPED because the post-load convergence gate failed**. No assertion or deadline was relaxed. No root cause is established by the throughput variation or this single backlog observation. The failed acceptance is retained rather than repeatedly retried until green.

The [sanitized fresh measurements](evidence/azure-revalidation-2026-10-26.json) preserve the failure. The original 213/s and 546 ms p95 figures remain a historical single-run observation; this rerun does not support treating them as repeatable capacity.

Cleanup: both the failed build attempt and the full rerun resource groups reached confirmed absence (`az group exists == false`). No Azure resources from either attempt remain in their resource groups. The ₹9,500 budget was a tracking budget, not a hard charge cap; actual billing totals were not measured here.

## Validation checklist

Focused local verification on the original snapshot passed **13 tests, zero failures/errors/skips**, using Linux Java 21 and disposable real PostgreSQL/Kafka Testcontainers: `IdempotencyIntegrationTest` 4/4, `AsyncFailureAndResilienceTest` 3/3, `ProjectionDeduplicationTest` 6/6. These cover API/service idempotency, real broker pause/recovery, relay failure simulation and durable projection deduplication/ordering. They are component/integration evidence, not a repeat of the skipped Azure outage/duplicate-consumption acceptance. The full suite and new remote CI were not run. Private log: `focused-regressions.log`.

| Requested check | Fresh status | Evidence and remaining limit |
|---|---|---|
| Rerun Azure benchmark with original workload/configuration | FAIL | Executed both original profiles; performance latency and convergence gates failed. |
| Verify counts, HTTP failures, throughput and p95 | PASS for receipt/measurement audit | Original and fresh k6 summaries/logs agree; original per-request samples are unavailable for an independent percentile reconstruction. |
| Repeat Kafka and consumer outage recovery | SKIPPED | Azure probe stopped at post-load convergence, before either outage. Historical receipts support the original 40-transfer test only. |
| Restore all 103,578 postings and compare 11 fingerprints | PASS, local scope | Hash-verified original backup restored; all 11 fingerprints matched in a fresh local roundtrip. Original live-source fingerprints were not separately retained. |
| Verify replay and duplicate consumption | PASS within local/API scope; cloud repeat SKIPPED | Fresh Azure API replay and all 13 focused local regressions passed. Cloud restart replay and duplicate Kafka consumption were not reached. |
| Balance invariants/reconciliation after failures | PARTIAL | Fresh cloud baseline/correctness and both local restored databases passed. Post-performance convergence failed; fresh post-outage reconciliation was not reached. |

The full six-item checklist is not unlocked. Preserve historical resume figures with their date and single-run limits; do not describe them as freshly reproduced. Local restoration is freshly verified. Cloud consistency recovery and duplicate-event claims retain historical evidence but were not repeated in this run.
