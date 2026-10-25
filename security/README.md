# Image vulnerability gate

Spring Boot 4.0.9 manages Spring Framework 7.0.10. This replaces the affected Spring MVC 6.2.19 dependency used by the previous release. The vendor fixes for CVE-2026-47884 and CVE-2026-47890 start at Spring 7.0.9.

The active policy has **zero applicability exceptions**. Its previous November 23 expiry is retired, not extended. Every HIGH/CRITICAL finding collected by the configured scanner is blocking; the raw Trivy JSON and separate disposition report remain CI artifacts. A historical finding or unrelated/new CVE cannot reuse the retired decisions. Scan failure, malformed output or a missing report is a failure.

The generic evaluator retains its bounded-review checks for any future explicitly reviewed decisions. Policy tests cover exact version scope, expiry, missing/failed/skipped receipts, and rejection of findings under the empty retired policy. The MVC boundary tests and unsafe XSLT/SSE negative controls remain as defense-in-depth regressions; they no longer authorize a vulnerability waiver.

Each image must be scanned anew. A patched dependency is not a claim that every present or future vulnerability is absent. The scanner configuration continues to collect HIGH/CRITICAL findings with available fixes; no broad ignore file was added.

Primary sources:

- https://spring.io/security/cve-2026-47884/
- https://spring.io/security/cve-2026-47890/
- https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide

The earlier reviews and raw receipts remain in the repository history and private evidence. Dated Boot 3 cloud evidence is historical and does not establish a Boot 4 cloud deployment.
