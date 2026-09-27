# Experiment 03: Minimal Cloud Deployment, Load Test, and Cost Teardown

## Status: AZURE EVIDENCE RUN COMPLETED

On 2026-09-27, LedgerLine was deployed to a short-lived Azure environment and validated against a public endpoint. The final automated run used Azure Container Registry for the application images, Azure Database for PostgreSQL Flexible Server for persistence, and a single Ubuntu VM running Docker containers for Kafka, `ledger-service`, and `projection-service`.

The resource group was deleted after the run. The final cleanup check returned `false` for `az group exists --name ledgerline-evidence-20260927183955`, so the evidence environment is no longer present.

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

Images were built locally and pushed to ACR because this subscription rejected ACR Tasks in earlier setup work. Central India accepted PostgreSQL creation but rejected `Standard_B2s`, so the final evidence run used `Standard_D2ls_v6`.

The automated script now uses the `correctness` k6 profile for low-cost single-VM evidence. The stricter `performance` profile remains available for larger VM classes or a separated Kafka/application topology.

## Evidence summary

| Check | Result | Evidence |
| --- | --- | --- |
| Public service health | `UP`, database health `UP` | [`03-azure-health.json`](../../bench/results/03-azure-health.json) |
| Smoke test | Passed account creation, funding, transfer, idempotent replay, balance check, postings history | [`03-azure-smoke.log`](../../bench/results/03-azure-smoke.log) |
| k6 load test | Completed 7,508 HTTP transfer requests with 7,508 successful transfers | [`03-azure-k6.log`](../../bench/results/03-azure-k6.log) |
| k6 thresholds | Passed correctness thresholds: p95 258.2ms under 1500ms, p99 341.58ms under 3000ms, HTTP failure rate 0.00% under 1% | [`03-azure-manual-k6-summary.json`](../../bench/results/03-azure-manual-k6-summary.json) |
| Ledger invariants after load | PostgreSQL invariant query completed with exit code 0 after k6 | [`03-azure-manual-invariants-summary.json`](../../bench/results/03-azure-manual-invariants-summary.json) |
| Cleanup | Delete requested, later confirmed absent with `az group exists == false` | [`03-azure-manual-cleanup-summary.json`](../../bench/results/03-azure-manual-cleanup-summary.json) |

The raw k6 JSON stream was generated locally as `bench/results/03-azure-k6.json`, but it is intentionally ignored because it is about 50 MB. The committed k6 log and summary capture the reviewable totals and threshold result.

## Honest interpretation

This run is strong evidence that the ledger can be deployed on Azure, process real HTTP traffic, persist to managed PostgreSQL, pass smoke checks, complete a low-cost correctness load profile, and run the PostgreSQL invariant checks afterward.

It is not evidence of production-ready capacity. The low-cost single-VM topology passed a realistic correctness profile at about 75 requests/sec with p95 258.2ms, but the result should not be generalized to higher sustained throughput. The next performance step is to run the stricter `performance` profile on a larger VM or separate Kafka/app compute, then repeat the same smoke, k6, invariant, and cleanup sequence.

## What changed after the first Azure observations

- The original aggressive p95 threshold was split into a low-cost `correctness` profile and a stricter `performance` profile.
- The VM Docker containers now use restart policies, resource caps, JVM memory flags, Hikari pool sizing, and Kafka producer/consumer tuning.
- The script waits for both ledger and projection service readiness before smoke/k6.
- Kafka receives a `kafka` Docker network alias so the broker and application bootstrap names match.
- Projection Flyway uses its own schema history table and baseline version 0 so it can share PostgreSQL with the ledger service without checksum collision.
- Cleanup now polls `az group exists` until Azure returns `false`.
- Azure budget creation remains manual because `az consumption budget create` rejected the resource-group filter syntax for this subscription/API path; cost control for the evidence run came from a short-lived resource group plus confirmed deletion.
