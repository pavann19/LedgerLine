# Ledgerline

OAuth2/JWT-secured double-entry ledger with CUSTOMER/OPERATOR authorization, account ownership, principal-scoped idempotency, attributed audit records, versioned APIs, RFC 7807 errors, OpenAPI, and cursor pagination.

Recorded local evidence: 19 ledger tests passed with 72.87% line coverage; both application images were built and SBOM-indexed at 178 packages each. Trivy currently reports one HIGH and three CRITICAL fixed-version findings per image; CI publishes the reports and the findings remain explicitly disclosed. Azure evidence run passed smoke checks, completed the low-cost correctness k6 profile with 7,508/7,508 successful HTTP transfers, 0.00% failure rate, and p95 258.2ms on `Standard_D2ls_v6`, then ran the PostgreSQL invariant query and confirmed resource-group cleanup.

Evidence: `bench/results/02-local-verification.json`, `bench/results/03-azure-manual-evidence-summary.json`, `bench/results/03-azure-smoke.log`, `bench/results/03-azure-k6.log`, `bench/results/03-azure-postgres-invariants.log`.
