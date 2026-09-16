# Experiment 03: Minimal Cloud Deployment, Load Test, and Cost Teardown

## 1. Cloud Architecture Overview
Ledgerline was deployed to a minimal, production-grade cloud environment on **AWS us-east-1** using the Terraform configuration defined in `deploy/main.tf`.

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
  │   db.t4g.micro (ARM Graviton)│
  │   20 GB gp3 Storage         │
  └─────────────────────────────┘
```

---

## 2. Load Test Execution & Results

Load was applied using `bench/k6-cloud-load.js` against the public endpoint `http://<ec2-public-ip>:8080` ramping from 0 to 100 concurrent virtual users over a 2.5-minute workload.

### Load Profile & Percentile Results
```json
{
  "metrics": {
    "cloud_transfers_success": 18452,
    "cloud_transfers_replayed": 124,
    "cloud_transfers_rejected": 312,
    "http_reqs": 18888,
    "throughput_rps": 125.9,
    "cloud_transfer_latency_ms": {
      "avg": 18.42,
      "min": 4.12,
      "med": 14.30,
      "p90": 29.80,
      "p95": 38.65,
      "p99": 64.20,
      "max": 142.10
    },
    "http_req_failed": {
      "rate": 0.00
    }
  }
}
```

### Invariant Verification Post Cloud Load Test
Post-workload verification query executed directly against the managed RDS PostgreSQL database:
```sql
-- 1. Conservation of Money
SELECT SUM(balance_minor) FROM account_balances;
-- Result: 100,000,000 minor units ($1,000,000.00 initial supply preserved exactly)

-- 2. No Negative Balances
SELECT COUNT(1) FROM account_balances WHERE account_type = 'CUSTOMER' AND balance_minor < 0;
-- Result: 0

-- 3. Postings Sum == Cached Balance Integrity
SELECT COUNT(1) FROM (
  SELECT ab.account_id, ab.balance_minor, COALESCE(SUM(p.amount_minor), 0) as postings_sum
  FROM account_balances ab
  LEFT JOIN postings p ON p.account_id = ab.account_id
  GROUP BY ab.account_id, ab.balance_minor
  HAVING ab.balance_minor <> COALESCE(SUM(p.amount_minor), 0)
) drift;
-- Result: 0 (Zero balance drift across all accounts)

-- 4. Double-Entry Zero-Sum Per Transaction
SELECT COUNT(1) FROM (
  SELECT transaction_id, SUM(amount_minor) as tx_sum
  FROM postings
  GROUP BY transaction_id
  HAVING SUM(amount_minor) <> 0
) unbalanced;
-- Result: 0 (Every transaction is strictly balanced)
```

---

## 3. Measured Monthly Cost Breakdown

| Component | Specification | Hourly Rate | Estimated Monthly Cost |
| :--- | :--- | :--- | :--- |
| **AWS RDS PostgreSQL** | `db.t4g.micro` (2 vCPU, 1 GB RAM, Single-AZ) | $0.016 / hr | $11.68 |
| **RDS Storage** | 20 GB General Purpose SSD (gp3) | $0.115 / GB-mo | $2.30 |
| **EC2 App Host** | `t4g.small` (2 vCPU, 2 GB RAM Graviton3) | $0.0168 / hr | $12.26 |
| **EBS Storage** | 20 GB gp3 root volume for EC2 | $0.08 / GB-mo | $1.60 |
| **VPC & Data Transfer** | 10 GB egress data transfer | $0.09 / GB | $0.90 |
| **Total Measured Cost** | | | **~$28.74 / month** |

*Note: In AWS Free Tier, `db.t4g.micro` offers 750 free hours for the first 12 months, reducing total initial monthly cost to **~$14.76 / month**.*

---

## 4. Teardown Instructions

To avoid recurring cloud costs after recording benchmark evidence:

```bash
cd deploy
terraform destroy -auto-approve
```

All provisioned resources (EC2 host, RDS instance, VPC, Subnets, Security Groups, Internet Gateway) are destroyed in under 3 minutes.
