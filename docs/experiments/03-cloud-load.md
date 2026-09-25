# Experiment 03: Minimal Cloud Deployment, Load Test, and Cost Teardown

## Status: NOT YET EXECUTED

This document previously contained a load-test JSON block, post-deploy SQL verification output, and a "Measured Monthly Cost" table presented as real results. None of them were produced by an actual `terraform apply` + running deployment — there is no committed deploy workflow run, no raw k6 output file, and no teardown record anywhere in this repo to back those numbers. They have been removed rather than corrected, because there is nothing in this environment (no AWS credentials, no permission to provision billed cloud infrastructure) to re-measure them from.

The architecture below is the deployment **as designed** in `deploy/main.tf` — described as intent, not as something that has run.

```
 Internet Clients (k6 Load Generator)
                 │
                 ▼
      [AWS VPC (10.0.0.0/16)]
                 │
  ┌──────────────┴──────────────┐
  │  Public Subnet (10.0.1.0/24) │
  │                             │
  │   [t4g.small Host]          │
  │   ├── Docker Compose        │
  │   ├── ledger-service:8080   │
  │   ├── projection-svc:8081   │
  │   ├── Apache Kafka (KRaft)  │
  │   └── Prometheus:9090       │
  └──────────────┬──────────────┘
                 │ (Internal Port 5432)
  ┌──────────────┴──────────────┐
  │  Private DB Subnet Group    │
  │                             │
  │   [AWS RDS PostgreSQL 16]   │
  │   db.t4g.micro (ARM Graviton2)│
  │   20 GB gp3 Storage         │
  └─────────────────────────────┘
```

Note: `t4g.*`/`db.t4g.*` instance families run on **Graviton2**, not Graviton3 (Graviton3 is used by the `c7g`/`m7g`/`r7g` families and newer). An earlier version of this document mislabeled the app host as Graviton3; corrected here.

The deployment path is now implemented but remains unexecuted. The manual workflow refuses to apply infrastructure unless it can verify an existing ACTUAL-cost AWS Budget notification, builds and pushes both images, supplies the JWT secret, runs smoke and k6 checks, queries RDS through SSM, records available Cost Explorer data, and destroys the stack in an `always()` step. Current status and missing claims are recorded in [`bench/results/03-aws-run-status.json`](../../bench/results/03-aws-run-status.json).

## To produce real evidence for this section

1. Configure the required GitHub secrets and create the named AWS Budget alert.
2. Explicitly authorize pushing this revision, then manually dispatch `.github/workflows/deploy.yml`.
3. Run `bench/smoke-test.sh` against the public URL from the Terraform output; commit its output/log.
4. Run `bench/k6-cloud-load.js` against the public URL; commit the raw JSON output (`--out json=...`) under `bench/results/`.
5. Run the four invariant SQL queries (conservation of money, no negative balances, postings-sum-equals-balance, zero-sum-per-transaction) directly against the RDS instance; commit the query output alongside the k6 JSON.
6. Record the actual AWS costs incurred (from the Cost Explorer / billing console for the exact window the environment existed), not list-price arithmetic.
7. Run `terraform destroy -auto-approve` and note the destroy timestamp here.
8. Only then, rewrite this document with real numbers, each one linking to the committed file it came from.

Until that happens, this experiment is documented as **not run**, and the README's cloud/cost section should not claim otherwise.
