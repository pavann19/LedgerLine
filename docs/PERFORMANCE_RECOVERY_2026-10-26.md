# Performance and recovery follow-up — 26 October 2026

**Final result: both SSD v2 performance passes exceeded the requested transfer-count/rate/p95 target and passed the complete bounded acceptance. Restoration, duplicate handling and financial reconciliation passed. Final observed recovery was 20.469/9.333 seconds, slightly above the original 20.422/8.892 observations; no recovery-speed improvement is claimed for the final run. All follow-up resource groups were deleted. Earlier failures remain recorded.**

## Change and correctness boundary

The projection previously sent eight sequential client/server statements per posting. The new `project_statement_posting` PostgreSQL function performs the projection in one client/server call per posting. It uses an append path when the event is at or after the current tail, and repairs the affected statement suffix and later daily summaries for late events. In-order events do not rewrite earlier statement history. Equal timestamps append by generated ID; PostgreSQL's persisted microsecond timestamp determines UTC summary dates.

The application still holds affected account advisory locks in deterministic order. Event-ID insertion, both postings and daily summaries remain in the same application transaction. PostgreSQL BIGINT overflow rejects the operation; the repository preserves the existing checked-money exception contract so the whole projection and processed-event marker roll back. No asynchronous acknowledgement or durability setting was weakened. Flyway migration `V3__transactional_posting_projection.sql` installs the function; do not deploy the repository change without the migration.

The first measured follow-up used the current branch's existing four-statement ledger reduction plus the projection change. The later follow-up also combines transaction header, paired postings, outbox and audit writes into one SQL command with data-modifying CTEs. Dependencies on the returned transaction header preserve the journal relationship; posting insertion explicitly orders debit before credit and returns stored IDs/timestamps. The pessimistic strategy acquires both balance locks in the existing Java UUID order using one command and updates both locked balances with another command. This removes five more client/server statements per fresh pessimistic transfer. Account/ownership/currency validation, principal-scoped idempotency locking and all rechecks, checked money arithmetic, deferred constraints and durable transaction commit remain active. Monetary fixtures, HTTP workload and acceptance thresholds are unchanged.

The final projection implementation uses V4's `project_statement_batch` function for each Kafka poll (at most 100 records). It deduplicates both within a batch and against durable markers, acquires account locks in the existing deterministic order, inserts debit/credit rows in arrival order, repairs each affected suffix once and applies each day's new flow/count totals once. It reads opening/closing balances through the account/date index rather than repeatedly aggregating the day's full history. Offset acknowledgement follows the whole database transaction commit. The V3 migration/function is retained for provenance/backward compatibility; it is not the final consumer's projection path. Internal SQL timestamps are explicitly ISO strings, independent of Kafka's numeric-Instant encoding.

## Local verification

Linux Java 21 verification passed 48 ledger tests. The final projection implementation then passed all 12 projection tests, including 7 durable PostgreSQL projection tests, with zero failures, errors or skips. The projection migration to V3 was observed in the test receipt. The added deterministic mixed-arrival test checks running balances and a full outer join of daily-summary values against a full-history SQL oracle after each of 80 events, with both transfer directions, late arrivals, equal timestamps and UTC day boundaries. Existing replay, overflow rollback, event ordering and unchanged-history checks remain active.

Private receipts: private local evidence (excluded from Git) and `projection-function-tests.log`. The first full run passed 60 tests on the initial append optimization; the final function implementation was subsequently recompiled and verified with the complete projection module. After the ledger batching change, `journal-tests-final.log` passed all 49 ledger tests, zero failures/errors/skips. Together the final ledger/projection implementations passed 61 tests. A new real-PostgreSQL regression rejects an invalid audit JSON value and requires the transaction header, postings, outbox and audit rows to all roll back. The three safe-strategy posting contract cases additionally require one outbox and audit record after replay. One earlier local test process was intentionally interrupted while finalizing the test/code changes; its incomplete log is not counted as PASS.

After V4 batching, `batch-projection-repaired-tests.log` passed all **15 projection tests**, giving **64 final tests** with the unchanged 49-test ledger result. Added cases exercise duplicate records within/across batches, cross-day full-history and daily-summary oracles, complete rollback of a batch containing an overflowing event, and the real consumer component's commit-before-acknowledgement/crash/redelivery path. An initial batch test run failed because the internal JSON boundary received numeric instants; that was corrected to explicit ISO timestamps before the passing run and before deployment. Real broker batch binding/delivery is additionally required from fresh Azure acceptance; the local consumer-component test alone does not prove that binding.

## Azure acceptance design

### First measured follow-up: projection function, original P4 storage

| Case | Transfers | Transfers/s | HTTP failures | p95 ms | p99 ms | Catch-up |
|---|---:|---:|---:|---:|---:|---:|
| Correctness, 100 s | 10,176 | 101.73 | 0 | 228.11 | 295.85 | 0.076 s |
| Performance, 150 s | 15,135 | 100.90 | 0 | 1,312.86 | 2,566.19 | 13.094 s |
| Performance repeat, 150 s | 15,972 | 106.46 | 0 | 1,156.76 | 2,065.40 | 15.280 s |

Both performance latency gates failed; both 180-second convergence gates and all post-load financial/projection SQL assertions passed. These measurements miss the requested throughput/latency target.

The consumer and Kafka outage drills each passed 20 transfers and complete reconciliation; observed restart-to-convergence times were 20.624 and 8.953 seconds. Ledger restart/replay passed in 9.095 seconds without adding postings. A duplicate event advanced committed offsets without adding projection or processed-event rows. The fresh 82,656-posting database restored with all 11 fingerprints matching. Separately, the exact hash-verified 103,578-posting retained backup restored on Azure, then passed a fresh dump/restore comparison of all 11 fingerprints and the complete SQL assertions. Final post-failure reconciliation passed. Both fingerprint maps are retained, rather than only their matching-table count. Result: `RECOVERY_PASS_LOAD_THRESHOLD_FAILED`. Cleanup reached confirmed resource-group absence.

A live wait snapshot observed tuple/transaction locks plus WAL write/sync waits. One-minute Azure metric buckets overlapping the first performance case reached 100% disk-IOPS consumption; database CPU maxima in overlapping load buckets remained below 27%. IOPS observations for the latest repeat were not available at immediate metric collection. These observations establish pressure/waits, not a complete causal attribution of every latency increase.

### Second measured follow-up: journal batching and P15 storage

The final attempt uses the ledger batching change and P15 storage performance, retaining 32 GiB capacity, the same VM/database compute and the same application allocations. The storage-performance change is explicit; a faster result cannot be attributed to code alone. It is not an A/B experiment on an otherwise identical database instance. The same correctness profile, two performance passes, recovery drills, duplicate processing and both restoration checks are required. Private receipts: private local evidence (excluded from Git).

| Case | Transfers | Transfers/s | HTTP failures | p95 ms | p99 ms | Catch-up |
|---|---:|---:|---:|---:|---:|---|
| Correctness, 100 s | 25,783 | 257.79 | 0 | 82.03 | 186.02 | 106.498 s, PASS |
| Performance, 150 s | 42,010 | 280.01 | 0 | 460.56 | 809.31 | FAIL at 180 s |
| Performance after relay tuning, 150 s | 28,816 | 192.09 | 0 | 644.88 | 1,129.97 | FAIL at 180 s |

The first performance case exceeds the requested timed target but is **not** a complete passing acceptance case: 135,594 ledger rows versus 133,358 projection rows and 1,186 unpublished events remained at its deadline. Only after that failure was recorded, a separate operator script restarted the ledger between timed profiles with the same image/CPU/memory and changed producer linger from 5 ms to 0. The relay synchronously waits for each acknowledgement, so the delay was counterproductive. The change was guarded against an active k6 process and is recorded in `relay-tuning.json`; the script is retained with the receipts. The original failure remains false in the unmodified report. The last profile ran with that new setting immediately after the restart; it is not an identical warm repeat of the first profile and is not pooled with it. At its deadline, 193,226 ledger rows versus 177,382 projection rows remained despite zero unpublished events, identifying remaining downstream work.

Additional settling allowed independent recovery/restoration checks after both failed deadlines; neither failure was converted to PASS. Recovery passed 40 transfers with observed consumer/Kafka recovery of 20.659/9.322 seconds. Ledger restart/replay passed in 9.194 seconds without extra postings. Duplicate consumption, complete final SQL checks, a 193,308-posting fresh backup/restore and the exact 103,578-posting retained fixture's Azure roundtrip all passed; both restored datasets matched all 11 table fingerprints. Raw result remains `RECOVERY_PASS_LOAD_THRESHOLD_FAILED`. Resource-group absence is confirmed.

### Third measured follow-up: batch projection, P15 and zero linger

This run started with batch projection and zero producer linger, without between-profile configuration changes. Private receipts: private local evidence (excluded from Git).

| Case | Transfers | Transfers/s | HTTP failures | p95 ms | p99 ms | Catch-up |
|---|---:|---:|---:|---:|---:|---:|
| Correctness, 100 s | 16,197 | 161.96 | 0 | 111.03 | 142.91 | 0.059 s |
| Performance, 150 s | 25,899 | 172.65 | 0 | 672.30 | 1,223.29 | 0.088 s |
| Performance repeat, 150 s | 26,012 | 173.40 | 0 | 678.96 | 1,181.08 | 0.076 s |

Every original latency/failure/convergence gate and complete financial/projection assertion passed. The stricter requested count/rate/p95 target was not met. Forty outage transfers and reconciliation passed; consumer/Kafka recovery observations were **16.083/7.842 seconds**, with ledger restart/replay 7.904 seconds and no added postings. Committed-offset-observed duplicate delivery passed. The fresh **136,306-posting** database and the retained **103,578-posting** fixture both passed Azure restoration and all 11 fingerprint comparisons. Raw result `PASS`; resource-group absence confirmed.

An independent read-only probe matched the **68,108 successful load responses** to exactly 68,108 database transactions, distinct load idempotency keys, outbox events and audit rows, plus **136,216 load postings**. The remaining 90 fresh-dataset postings belong to the probe/funding/recovery transactions, not timed load. Probe source and counts are retained with receipt hashes. This verifies the HTTP count against durable records rather than relying only on the k6 counter.

Database roundtrip timing was approximately 0.55–0.60 ms after its first query, comparable to the earlier run. Aggregate diagnostic maxima were CPU 34.74%, memory 19.65% and IOPS consumption 20%; this does not establish saturated compute, IOPS or a network-placement cause. Remaining durable-write latency is a hypothesis, not a proven full attribution.

### Fourth measured follow-up: same application, Premium SSD v2

The same application sources, workload, compute and container limits used **32 GiB `PremiumV2_LRS`, 3,000 IOPS and 125 MB/s**, confirmed by Azure's configuration response. This changes storage from the original P4/120-IOPS run; it is not a performance gain on identical original hardware or a code-only/identical-physical-host A/B test. [Microsoft documents the storage characteristics and regional support](https://learn.microsoft.com/en-us/azure/postgresql/compute-storage/concepts-storage-premium-ssd-v2). Private receipts: private local evidence (excluded from Git). No between-profile configuration change occurred.

| Case | Transfers | Transfers/s | HTTP failures | p95 ms | p99 ms | Catch-up |
|---|---:|---:|---:|---:|---:|---:|
| Correctness, 100 s | 28,882 | 288.81 | 0 | 64.88 | 114.90 | 0.075 s |
| Performance, 150 s | **48,427** | **322.83** | **0** | **362.01** | 628.22 | 12.634 s |
| Performance repeat, 150 s | **49,472** | **329.80** | **0** | **341.11** | 593.77 | 14.745 s |

Both performance cases exceeded 32,018 transfers and had p95 below the original 545.56289645 ms. All original p95/p99/failure gates and 180-second convergence deadlines passed, followed by the full-history monetary, statement and daily-summary assertions. These are two bounded, sequential runs with growing history and warmed services, not a sustained production SLO or a latency distribution across repeated deployments. Percentiles are k6's HTTP-duration summaries, checked against its logs; individual request samples were not retained for independent percentile reconstruction. The two cases are not pooled into a percentile or a code-only percentage improvement.

The independent database audit matched **126,781 successful timed-load responses** with exactly **126,781 transactions, distinct idempotency keys, outbox rows and audit rows**, and **253,562 postings**. The final source contained **253,652 postings**, including 90 postings from the operator probes/funding/recovery. No HTTP 200 replay or funding/recovery transfer was counted as a successful timed-load HTTP 201 transfer.

The consumer/Kafka interruption drills each passed 20 transfers: **40 total**, with observed recovery **20.469/9.333 seconds**. These final observations are slightly higher than the historical 20.422/8.892; they must not be rounded/relabelled as an improvement. The earlier batch/P15 run separately observed 16.083/7.842 seconds; those are a different run and are not substituted into the SSD v2 result. Ledger restart/replay passed in **9.179 seconds**, returning the original transaction with no extra postings. A duplicate event advanced committed offsets without adding statement or processed-event rows.

The quiesced fresh **253,652-posting** database restored into a separate database with **all 11 fingerprints matching**. Separately, the hash-verified historical **103,578-posting** backup restored on Azure and passed another fresh dump/restore of **all 11 fingerprints**. Both complete fingerprint maps were retained and independently checked in the downloaded receipts. This is a new roundtrip of retained historical data, not a fresh comparison to the deleted original live source. Dump/restore command times for the fresh dataset were 11.968/7.306 seconds; these exclude quiescing and other validation and are not DR RTO/RPO.

Final SQL asserted zero posting sum, negative customer balances, cached-balance mismatches, unbalanced transactions, statement drift and daily-summary drift, with ledger/projection row counts equal. All assertions passed. Raw acceptance result: **PASS**. All test resource groups reached confirmed absence; actual billing totals were not measured. The implementation and sanitized evidence are included in the local tag `ledgerline-azure-validation-2026-10-26`. No public push occurred.

## Reproduce the final configuration

Check out tag `ledgerline-azure-validation-2026-10-26` for the ledger write implementation, both V3 and V4 projection migrations, tests, runner and sanitized evidence. The measured source snapshots and manifests remain private; their digests are retained in the sanitized evidence.

Supply operator-selected private locations in `$RetainedBackupPath` and `$EvidenceDirectory`; neither location is published.

```powershell
./scripts/azure-evidence-run.ps1 -RemoteOnly -RecoveryChecks -PerformanceRepeats 2 `
  -VmSize Standard_D4ls_v6 -PostgresSku Standard_D2ds_v5 -PostgresTier GeneralPurpose `
  -PostgresStorageType PremiumV2_LRS -PostgresStorageIops 3000 -PostgresStorageThroughput 125 `
  -LedgerMemory 2g -LedgerCpu 1.75 -ProjectionMemory 1400m -ProjectionCpu 0.75 `
  -RetainedBackupPath $RetainedBackupPath -BudgetAmount 9500 `
  -EvidenceDirectory $EvidenceDirectory
```

The retained-backup option is optional for ordinary acceptance; exact reproduction of the historical 103,578-posting fixture needs that private backup. Default fresh-dataset restoration remains part of `RecoveryChecks`. Paid, disposable resources are deleted in `finally`; image builds now happen before provisioning. The budget is a tracking budget, not a hard charge cap. Do not point the probes at an existing or production host/database.

Batch poison-message/error-recovery behavior remains unverified. Successful duplicate delivery and the tested failures do not prove every Kafka consumer failure path or production reliability.

Original configuration: Central India, `Standard_D4ls_v6` VM, managed PostgreSQL `Standard_D2ds_v5` General Purpose, 32 GiB P4 / 120 IOPS, original application CPU/memory allocations, and unchanged two-account k6 workload. The correctness profile runs once; the original 150-second performance profile runs twice on the same growing database and warmed services. This is repeated candidate acceptance, not an A/B code-only improvement experiment or identical-starting-dataset comparison.

The original p95 <750 ms, p99 <1,500 ms, HTTP failure rate <1%, and 180-second convergence gate remain active. The requested historical target is stricter: at least 32,018 successful transfers in 150 seconds, with approximately 213/s and p95 no worse than 545.56289645 ms. Meeting the original acceptance gate alone does not establish that target.

If convergence misses 180 seconds, that gate remains failed. A separately labelled additional settling phase allows independent recovery drills to proceed if safe convergence is subsequently observed; it cannot turn the failed gate into PASS. Recovery tests still perform 20 transfers with the consumer stopped and 20 with Kafka stopped, then check database and projection invariants. Replay and committed-offset-observed duplicate consumption remain mandatory.

The fresh dataset is dumped/restored into another database and all 11 table fingerprints are retained. Separately, the hash-verified original 103,578-posting backup is restored on Azure and subjected to another fresh dump/restore and all 11 fingerprint comparisons. That fixture test restores retained historical data; it is not a new comparison against the deleted historical live database. Both fixture databases must pass the original complete SQL assertions.

Private output directory for the measured retry: private local evidence (excluded from Git). The earlier `azure/` attempt stopped at image compilation because an import used by the daily-summary query had been removed after the test compile. The import was restored; both final application images built successfully locally. That attempt ran no benchmark, and its resource group was confirmed deleted. The runner now builds both images before provisioning any Azure resources.

The tagged source tree includes both function migrations and the new ledger repository. Private measurement-time snapshots and source hashes preserve provenance independently of the earlier parent commit. Optional database roundtrip timings, database counters, Azure resource metrics and container samples provide diagnostics. Roundtrip timing excludes connection setup and is not application latency.

Every measured attempt is retained. Resource-group deletion and confirmed absence are required before delivery. The ₹9,500 tracking budget is not a hard spending cap. No public push or deployment is part of this follow-up.

See [sanitized follow-up evidence](evidence/performance-recovery-2026-10-26.json).
