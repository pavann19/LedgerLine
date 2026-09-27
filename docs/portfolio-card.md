# Ledgerline

OAuth2/JWT-secured double-entry ledger with CUSTOMER/OPERATOR authorization, account ownership, principal-scoped idempotency, attributed audit records, versioned APIs, RFC 7807 errors, OpenAPI, and cursor pagination.

Recorded local evidence: 19 ledger tests passed with 72.87% line coverage; both application images were built and SBOM-indexed at 178 packages each. Trivy currently reports one HIGH and three CRITICAL fixed-version findings per image; CI publishes the reports and the findings remain explicitly disclosed. Azure evidence run passed smoke checks and post-load ledger invariants after 13,062 k6 iterations; the latency threshold failed on the low-cost single-VM topology, so this is correctness/deployment evidence rather than a production latency claim.

Evidence: `bench/results/02-local-verification.json`, `bench/results/03-azure-manual-evidence-summary.json`, `bench/results/03-azure-smoke.log`, `bench/results/03-azure-k6.log`, `bench/results/03-azure-postgres-invariants.log`.
