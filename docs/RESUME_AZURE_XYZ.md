# LedgerLine — measured Azure résumé bullets

- Validated a double-entry financial backend at 213 transfers/second and 546 ms p95 latency with zero HTTP failures in a 150-second Azure test (32,018 transfers; up to 100 virtual users), using Java 21, Spring Boot 4, PostgreSQL and Kafka.
- Preserved ledger/projection consistency across 40 transfers during injected consumer and Kafka outages by using a transactional outbox and idempotent consumption; observed recovery took 20.4 seconds and 8.9 seconds after restart.
- Verified restoration of a PostgreSQL dataset containing 103,578 ledger postings by automating logical backup/restore into a separate database and comparing all 11 table fingerprints.

Interview context: bounded synthetic cloud tests, not production banking. Load generator shares the 4-vCPU / 8-GiB VM with the services; managed PostgreSQL uses D2ds_v5 with 32 GiB / 120 IOPS. Recovery timings are single observations. The restore used quiesced writers and a separate database on the same server; it is not cross-region disaster recovery. Both completed profiles total 51,744 transfers with zero HTTP failures and zero final financial/projection drift. No percentage performance improvement is claimed.

Evidence: D:/LedgerLine/docs/AZURE_BOOT4_VALIDATION.md
