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

Both exact images were scanned. LZ4 uses patched 1.11.4. Raw reports retain CVE-2026-47884 and CVE-2026-47890 in Spring MVC 6.2.19; these are assessed not_affected for the tested REST-only application configuration, not patched or absent. See [the exact applicability policy](../security/README.md). Decisions expire on 2026-11-23; other findings, missing/failed MVC tests, or expired decisions fail the gate. A zero-findings scan is not claimed.

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
