# LedgerLine documentation

Start with the [project overview](../README.md) and [verification status](VERIFICATION.md). The latter identifies the exact reviewed source revision and separates hosted checks from operational evidence.

## Guides

- [ARCHITECTURE](ARCHITECTURE.md)
- [FINANCIAL INVARIANTS](FINANCIAL_INVARIANTS.md)
- [API SECURITY](API_SECURITY.md)
- [DATABASE DEPLOYMENT](DATABASE_DEPLOYMENT.md)
- [TESTING](TESTING.md)
- [RECOVERY AND RECONCILIATION](RECOVERY_AND_RECONCILIATION.md)
- [api-examples](api-examples.md)
- [README](../reconciliation-agent/README.md)

## Reading paths

For evaluation, read architecture, verification status and test limitations. For installation, follow the deployment or demo guide before changing an environment. For maintenance, read recovery instructions and the optional worker guide separately: the worker is not the authority for admission, consensus or financial state.

Commands run from the repository root unless stated otherwise. Historical measurements describe their original revision and environment; they are not current capacity or production availability claims. A finite passing test is not a 24-hour reliability result. Preserve failed evidence when verifying a repair.

Source licensing is in [LICENSE](../LICENSE) and [third-party notices](../THIRD_PARTY_NOTICES.txt). Review distribution obligations again when producing binaries or containers.

Contributor workflow: [contributing](../CONTRIBUTING.md), [maintenance](../MAINTAINING.md), [security reporting](../SECURITY.md).
