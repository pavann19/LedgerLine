# Image vulnerability applicability

Raw Trivy JSON and per-finding dispositions are both retained as CI artifacts. The scan collection step returns success to allow assessment; `evaluate-image-scan.py` is the blocking gate. Every HIGH/CRITICAL finding fails unless it exactly matches a current, version-specific decision and the MVC boundary regressions from both services passed. Missing or malformed evidence fails the build. Scan failures and missing report files are not passes.

The XSLT decision concerns Spring MVC 6.2.19 / CVE-2026-47884. The upstream advisory requires XsltView and an implicit wildcard view-rendering mapping. LedgerLine uses REST response bodies with no XSLT resolver/view. Tests inspect registered application handlers in both MVC slices, prohibit view return types, and include a deliberately unsafe view-rendering negative control. This is an application applicability assessment, not a patched-library or zero-vulnerability claim. Custom configuration or a new view integration requires reassessment.

Primary advisory: https://spring.io/security/cve-2026-47884/

The decision expires on 2026-11-23 and applies only to the exact package/version. It must be renewed by review or replaced by a supported dependency update; expiry fails closed. Other CVEs, versions, or packages remain blocking. `python scripts/test_image_scan_policy.py` exercises unrelated findings, wrong versions, missing receipts, expiry, and future review dates. No `.trivyignore` or broad ignore rule is used.

CI downloads the same run's surefire artifact after the full test job succeeds. Local replay of an older raw report is a policy test, not a current image scan. The build must always scan its exact image anew.

Review on 2026-10-24 rechecked both upstream advisories, both services' MVC boundary guards and their unsafe-view/SSE negative controls. Published CI run [40068424035](https://github.com/pavann19/LedgerLine/actions/runs/40068424035) passed the full suite and both exact-image gates. The existing 2026-11-23 expiry is unchanged. This review does not authorize new view/SSE features or claim the affected library version is patched. Before expiry, check supported fixed dependencies, repeat the boundary tests and exact-image scans, and update or remove each decision based on that evidence; do not automatically extend the dates.

A second exact-version decision covers CVE-2026-47890 (SSE view-fragment stream corruption). Neither service exposes SSE or view fragments; return-type checks and an unsafe SSE fixture guard this condition in both modules. Reference: https://spring.io/security/cve-2026-47890/
