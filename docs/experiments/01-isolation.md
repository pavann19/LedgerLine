# Isolation experiment

IsolationExperimentTest compares an unlocked READ COMMITTED negative control, pessimistic locks, optimistic versions, and SERIALIZABLE retries. Seeded initial balances remain unchanged; assertions compare posting deltas and cached balances. The broken control uses contended trials and a delay; anomaly reproduction is timing-dependent.

bench/k6-isolation-test.js, seed_accounts.py, analyze_results.py, and compressed bench/results/01-* preserve the original local experiment. They are HISTORICAL and have not been rerun for the changed tree. No universal throughput figure is published.

The public API rejects variant 0. The retained HTTP workload therefore cannot reproduce its broken control through the public API; use the internal database integration experiment. The workload is preserved instead of edited to hide the contract change. Safe comparison variants 1-3 remain accepted; pessimistic is default.

Report hardware, revision, database-backed workload, VUs, contention, failures, and invariant checks with any new benchmark. Compile/mocked test results are not performance evidence.
