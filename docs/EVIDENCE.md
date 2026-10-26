# Evidence boundaries

The portfolio scope is a reproducible financial-backend demonstration with local validation and a temporary Azure acceptance run. It is not production banking software, a continuously operated managed-cloud service, or a capacity guarantee.

## Reproduce verification

```powershell
.\mvnw.cmd clean verify -Pcoverage --batch-mode
python scripts/test_image_scan_policy.py
powershell -ExecutionPolicy Bypass -File scripts/verify-local-stability.ps1
```

The Java suite requires real PostgreSQL/Kafka Testcontainers. The stability script builds/starts the local stack, runs the unchanged k6 correctness workload, verifies scoped financial and projection invariants against full-history SQL calculations, and reruns the evaluator and API probes on the same database and broker. It does not clear database volumes or reset offsets. Cold builds have no fixed five-minute guarantee.

Local validation on 2026-10-24 passed 53 Java tests under Java 21 Linux, four scan-policy tests, and 16 live API probes. The measured local load run completed 6,607 successful transfers with zero HTTP failures; scoped balance, statement, and daily-summary drift were zero. The post-load evaluator passed. The load run overlapped other local verification on a constrained Docker host, so its rate is not a dedicated server-capacity benchmark.

## Published CI evidence

[Post-merge release CI](https://github.com/pavann19/LedgerLine/actions/runs/40069310183) passed on main commit `8863de00a75997f27172d6ed26a43c5e17777e80`. [Cloud-runner CI](https://github.com/pavann19/LedgerLine/actions/runs/40071637146) passed on `774ee39c74132288169280a59600f05b5fcdd502`, the revision used for the final Azure run. Both passed the 53 Java tests, four scan-policy tests and both image gates.

[GitHub Actions run 40068424035](https://github.com/pavann19/LedgerLine/actions/runs/40068424035) passed on commit `578c552df12bd8200c9d1e8536eb0f9c0a36de28` on 2026-10-24: 43 ledger tests, 10 projection tests, four policy tests, both image builds, SBOM generation and both contextual vulnerability gates. Test failures, errors and skips were zero. The run retains test/coverage and supply-chain artifacts subject to GitHub retention. The load and live API checks above were local; this CI run does not execute them. Later revisions require their own successful run.

For the historical Spring Boot 3 release, both exact images were scanned. LZ4 uses patched 1.11.4. Raw reports retain CVE-2026-47884 and CVE-2026-47890 in Spring MVC 6.2.19; these are assessed not_affected for the tested REST-only application configuration, not patched or absent. See [the exact applicability policy](../security/README.md). Those historical decisions were scheduled to expire on 2026-11-23 and have now been retired by the Spring Boot 4 migration below. The historical scan was not a zero-findings result.

## Temporary Azure verification

Executed on 2026-10-24 with revision `774ee39c74132288169280a59600f05b5fcdd502`: Ubuntu VM `Standard_D2ls_v6` in Central India, managed PostgreSQL 16 `Standard_B1ms`, a private image registry and Kafka. APIs bound to VM loopback and were reached through an SSH tunnel restricted to the operator address. This was a disposable acceptance environment, not a public service.

| Check | Observed result |
|---|---|
| Initial smoke and original-transaction replay | PASS |
| Unchanged k6 correctness workload, 100 seconds / peak 20 VUs | 5,235 successful transfers, zero HTTP failures; original thresholds passed |
| Smoke repeated on the populated database/broker | PASS, without resets |
| VM-local API acceptance probe | PASS; 63 HTTP checks including catch-up polling, not 63 distinct test cases |
| Checked API boundaries | Replay/conflicting replay, authentication, ownership, privileged account creation, invalid amounts and blocked unsafe strategy |
| Probe account balances | 87,500 / 12,500 / -100,000 minor units |
| Global financial SQL assertions | Posting sum 0; negative customer balances 0; cached balance mismatches 0; unbalanced transactions 0 |
| Projection SQL oracle | 10,484 ledger postings = 10,484 statement rows; statement drift 0; daily-summary drift 0 |
| Exact running images | Matched the locally scanned image IDs below; both applicability gates passed using successful CI receipts |
| Cleanup | All three temporary attempt groups confirmed absent; final runner recorded verificationPassed and cleanupConfirmedAbsent |

Running/scanned image IDs:

- Ledger: `sha256:1f1c7b896aaca6c2f60a3a3c46b4ac4d1d9b5093ed8f9175901039e9f39a57c4`.
- Projection: `sha256:8b3a33bac457ed7f544468427db53795b94b83d6e84ccb9251a7b5e2b139285d`.

Two earlier attempts were not counted as automated passes: one stopped on incorrect PostgreSQL firewall CLI arguments; the next passed application/load checks and an independent SQL check but failed to capture the runner's SQL receipt through the Windows CLI shim. Both groups were deleted. The corrected final runner encoded its SQL transport, retained the assertion receipt, and completed verification and cleanup successfully.

The cloud load used a local client over SSH and shared a small VM with Kafka and both services. It is not an isolated server-capacity benchmark. The global SQL check ran after a settling period and remote command latency; it does not establish a 60-second post-load cloud catch-up bound. The API probe separately bounded its own statement polling. A budget record was created in billing currency; it was not a hard spending cap. Raw billing output is private and no final cost amount is claimed.

Reproduction is a paid manual operation; see [deployment requirements](DATABASE_DEPLOYMENT.md#temporary-azure-verification). Raw cloud logs, assertion receipts, scans and cleanup records remain private. There is no ongoing hosted endpoint.

The demo generates a random signing secret unless one is supplied. Rekeying invalidates old tokens. Its ignored summary contains local tokens and must not be published. Before a new run the old summary is backed up to an ignored previous-summary file, so a failed run cannot leave old results looking current.

Compressed isolation inputs/results are HISTORICAL single-machine observations, not current security/capacity evidence. Generated reports, cloud logs, and private presentation notes are excluded from public source. Raw local logs, image IDs, source hashes, and test receipts are retained outside the checkout.

Production operation, real-money use, high availability, exactly-once end-to-end processing, and complete failure injection are UNVERIFIED. Historical SQL-funded/broken-experiment volumes are not clean accounting baselines; preserve them and use a separate fresh environment for clean evaluation. See [testing limitations](TESTING.md).


## Spring Boot 4 migration — 2026-10-25

Spring Boot 4.0.9 / Spring Framework 7.0.10 preserves Java 21, financial assertions, SQL migrations and benchmark workloads. Jackson 3 compatibility tests preserve integer money values, record field order and numeric nanosecond event timestamps, including reading legacy Jackson 2 events. Boot-specific MVC, security, Kafka, Flyway and test starters replace the older starter layout. Released dependencies resolve through the project-local Maven Central mirror.

[Migration CI](https://github.com/pavann19/LedgerLine/actions/runs/40176097472) passed 55 Java tests with zero failures, errors or skips, five policy tests, both images and their security gates. Both previous Spring MVC applicability exceptions are retired; the policy contains no active exceptions.

Local Java 21 container validation completed the unchanged 100-second, 20-VU workload: 4,762 successful transfers, zero HTTP failures, 9,527 scoped ledger and projection rows, and zero balance, statement or daily-summary drift. The reused-stack evaluator and all 16 API regressions passed. This is a bounded local correctness run, not a dedicated capacity benchmark. The earlier Azure acceptance remains evidence for the historical Boot 3 revision; At this migration gate, Boot 4 had not yet been deployed to Azure; the subsequent cloud verification is recorded below.

Both exact local runtime images also passed Trivy 0.74.0 with zero findings under the configured HIGH/CRITICAL, fixed-vulnerability filter and zero applicability exceptions. This does not claim all-severity or unfixed-vulnerability absence. Both public OpenAPI endpoints returned HTTP 200. Metrics remain denied by the existing security policy.

For existing-data compatibility, a read-only copy of the stopped, previously verified PostgreSQL volume was started separately. Both Boot 4 services reached health UP with outbox relay and Kafka consumption disabled; Flyway validated the existing ledger V4 and projection V2 histories. Counts and content hashes of all nine application tables and both migration-history tables were identical before and after startup. The original database was not modified. This verifies data-preserving startup, not an online rolling upgrade or mixed-version operation.


## Boot 4 Azure performance and recovery - 2026-10-25

The [completed cloud acceptance](AZURE_BOOT4_VALIDATION.md) passed the unchanged correctness and performance profiles on a dedicated 4-vCPU / 8-GiB Azure VM with managed PostgreSQL D2ds_v5 (32 GiB / 120 IOPS): 19,726 transfers / 100 seconds at 110 ms p95 and 32,018 transfers / 150 seconds at 546 ms p95, both with zero HTTP failures. These are bounded staged tests with peak 20/100 virtual users; the generator shares the VM with the services. No production capacity or percentage-improvement claim is made.

The full-history assertions passed after asynchronous catch-up. Forty transfers succeeded during separate projection/Kafka interruptions; observed recovery after restart was 20.422 / 8.892 seconds. Ledger restart replay added no postings, and a duplicate event advanced consumer offsets without adding projection rows. A quiesced PostgreSQL logical backup restored 103,578 ledger postings into a separate database on the same managed server; all 11 application/migration table fingerprints matched. Final ledger/projection counts both equaled 103,578 with zero financial, statement or daily-summary drift. The on-host acceptance probe passed 22 HTTP checks including polling.

Both exact images passed the configured HIGH/CRITICAL fixed-vulnerability gate with zero active exceptions. All three temporary resource groups (including two incomplete earlier attempts) were confirmed deleted. Raw receipts and the synthetic backup remain private; the [sanitized measurements](evidence/azure-boot4-2026-10-25.json), [chart](images/azure-validation-2026-10-25.png) and [resume wording](RESUME_AZURE_XYZ.md) are reviewable artifacts. Earlier failed/incomplete runs and the test limitations are documented in the full report.


## 2026-10-26 transfer query optimization

Application correctness: PASS (59 Linux Java 21 tests; zero failures/errors/skips). Fresh transfers remove four DB statements using paired INSERT RETURNING and locked-account metadata reuse. Five image-policy controls and both exact-image fixable HIGH/CRITICAL gates passed with zero exceptions.

Repeated Azure performance validation: FAILED / INCOMPLETE. The P4 experiment stopped on case 4; the P15 repeat stopped on case 2. Six load samples across both experiments completed 143,706 successful transfers with zero HTTP failures. Four full post-load SQL validations passed; two were not reached because projection catch-up exceeded 180 seconds. These samples are not a completed six-case controlled comparison and are not pooled into a gain claim. The P15 failed observation had 24,354 postings, 18,268 statement rows and zero unpublished outbox records.

Both temporary resource groups were confirmed absent; temporary registry credentials were removed. Source changes remain local on transfer-query-optimization. See [full scope, measurements and limits](TRANSFER_QUERY_OPTIMIZATION.md) and [sanitized receipt](evidence/transfer-query-optimization-2026-10-26.json).
