# Experiment 03: Minimal Cloud Deployment, Load Test, and Cost Teardown

## Status: AZURE EVIDENCE RUN COMPLETED

On 2026-09-27, LedgerLine was deployed to a short-lived Azure environment and validated against a public endpoint. The run used Azure Container Registry for the application images, Azure Database for PostgreSQL Flexible Server for persistence, and a single Ubuntu VM running Docker containers for Kafka, `ledger-service`, and `projection-service`.

The resource group was deleted after the run. The final cleanup check returned `false` for `az group exists --name ledgerline-evidence-20260927162429`, so the evidence environment is no longer present.

## Azure topology used for the evidence run

```
 Internet Clients (smoke test + k6)
                 │
                 ▼
      [Azure VM: Standard_D2ls_v6]
                 │
  ┌──────────────┴──────────────┐
  │ Docker network: ledgerline   │
  │                              │
  │  ledger-service:8080         │
  │  projection-service:8081     │
  │  Apache Kafka 3.8.0 (KRaft)  │
  └──────────────┬──────────────┘
                 │ TLS / public PostgreSQL endpoint
                 ▼
  [Azure Database for PostgreSQL Flexible Server 16]
```

Images were built locally and pushed to ACR because this subscription rejected ACR Tasks for the run. Central India accepted PostgreSQL creation but rejected `Standard_B2s`, so the final evidence run used `Standard_D2ls_v6`.

## Evidence summary

| Check | Result | Evidence |
| --- | --- | --- |
| Public service health | `UP`, database health `UP` | [`03-azure-health.json`](../../bench/results/03-azure-health.json) |
| Smoke test | Passed account creation, funding, transfer, idempotent replay, balance check, postings history | [`03-azure-smoke.log`](../../bench/results/03-azure-smoke.log) |
| k6 load test | Completed 13,062 iterations with 12,947 successful transfers and 115 rejected transfers | [`03-azure-k6.log`](../../bench/results/03-azure-k6.log) |
| k6 thresholds | Failed latency thresholds: p95 was 1.24s against a 150ms threshold; HTTP failure rate was 0.88%, below the 1% threshold | [`03-azure-manual-k6-summary.json`](../../bench/results/03-azure-manual-k6-summary.json) |
| Ledger invariants after load | Passed: global posting sum 0, no negative customer balances, no cached-balance mismatches, no unbalanced transactions | [`03-azure-postgres-invariants.log`](../../bench/results/03-azure-postgres-invariants.log) |
| Cleanup | Delete requested, later confirmed absent with `az group exists == false` | [`03-azure-manual-cleanup-summary.json`](../../bench/results/03-azure-manual-cleanup-summary.json) |

The raw k6 JSON stream was generated locally as `bench/results/03-azure-k6.json`, but it is intentionally ignored because it is about 50 MB. The committed k6 log and summary capture the reviewable totals and threshold result.

## Honest interpretation

This run is strong evidence that the ledger can be deployed on Azure, process real HTTP traffic, persist to managed PostgreSQL, and preserve double-entry accounting invariants after load.

It is not evidence of production-ready latency. The low-cost single-VM topology kept correctness intact, but p95 latency exceeded the aggressive threshold. The next performance step is to separate Kafka/app compute, add service readiness/restart policies, tune connection pools, and rerun the same evidence workflow with a target p95 suitable for the chosen VM class.
