# Evidence boundaries

The portfolio scope is a reproducible local financial-backend demonstration. It is not production banking software, a managed-cloud service, or a capacity guarantee.

## Reproduce verification

```powershell
.\mvnw.cmd clean verify -Pcoverage --batch-mode
python scripts/test_image_scan_policy.py
powershell -ExecutionPolicy Bypass -File scripts/verify-local-stability.ps1
```

The Java suite requires real PostgreSQL/Kafka Testcontainers. The stability script builds/starts the local stack, runs the unchanged k6 correctness workload, verifies scoped financial and projection invariants against full-history SQL calculations, and reruns the evaluator and API probes on the same database and broker. It does not clear database volumes or reset offsets. Cold builds have no fixed five-minute guarantee.

Local validation on 2026-10-24 passed 53 Java tests under Java 21 Linux, four scan-policy tests, and 16 live API probes. The measured local load run completed 6,607 successful transfers with zero HTTP failures; scoped balance, statement, and daily-summary drift were zero. The post-load evaluator passed. The load run overlapped other local verification on a constrained Docker host, so its rate is not a dedicated server-capacity benchmark.

## Published CI evidence

[GitHub Actions run 40068424035](https://github.com/pavann19/LedgerLine/actions/runs/40068424035) passed on commit `578c552df12bd8200c9d1e8536eb0f9c0a36de28` on 2026-10-24: 43 ledger tests, 10 projection tests, four policy tests, both image builds, SBOM generation and both contextual vulnerability gates. Test failures, errors and skips were zero. The run retains test/coverage and supply-chain artifacts subject to GitHub retention. The load and live API checks above were local; this CI run does not execute them. Later revisions require their own successful run.

Both exact images were scanned. LZ4 uses patched 1.11.4. Raw reports retain CVE-2026-47884 and CVE-2026-47890 in Spring MVC 6.2.19; these are assessed not_affected for the tested REST-only application configuration, not patched or absent. See [the exact applicability policy](../security/README.md). Decisions expire on 2026-11-23; other findings, missing/failed MVC tests, or expired decisions fail the gate. A zero-findings scan is not claimed.

The demo generates a random signing secret unless one is supplied. Rekeying invalidates old tokens. Its ignored summary contains local tokens and must not be published. Before a new run the old summary is backed up to an ignored previous-summary file, so a failed run cannot leave old results looking current.

Compressed isolation inputs/results are HISTORICAL single-machine observations, not current security/capacity evidence. Generated reports, cloud logs, and private presentation notes are excluded from public source. Raw local logs, image IDs, source hashes, and test receipts are retained outside the checkout.

Production operation, real-money use, high availability, exactly-once end-to-end processing, and complete failure injection are UNVERIFIED. Historical SQL-funded/broken-experiment volumes are not clean accounting baselines; preserve them and use a separate fresh environment for clean evaluation. See [testing limitations](TESTING.md).
