# Ledgerline

OAuth2/JWT-secured double-entry ledger with CUSTOMER/OPERATOR authorization, account ownership, principal-scoped idempotency, attributed audit records, versioned APIs, RFC 7807 errors, OpenAPI, and cursor pagination.

Recorded local evidence: 19 ledger tests passed with 72.87% line coverage; both application images were built and SBOM-indexed at 178 packages each. Trivy currently reports one HIGH and three CRITICAL fixed-version findings per image, so the supply-chain CI gate remains intentionally blocking. The AWS experiment is explicitly not run.

Evidence: `bench/results/02-local-verification.json`, `bench/results/03-aws-run-status.json`.
