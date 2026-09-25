\pset pager off
\echo 'global posting sum must be zero'
SELECT COALESCE(SUM(amount_minor), 0) AS global_posting_sum FROM postings;
\echo 'customer balances must be non-negative'
SELECT COUNT(*) AS negative_customer_balances
FROM account_balances b JOIN accounts a ON a.id = b.account_id
WHERE a.account_type = 'CUSTOMER' AND b.balance_minor < 0;
\echo 'cached balances must equal posting sums'
SELECT COUNT(*) AS cached_balance_mismatches
FROM account_balances b
LEFT JOIN (SELECT account_id, SUM(amount_minor) AS total FROM postings GROUP BY account_id) p
  ON p.account_id = b.account_id
WHERE b.balance_minor <> COALESCE(p.total, 0);
\echo 'every transaction must be zero sum'
SELECT COUNT(*) AS unbalanced_transactions
FROM (SELECT transaction_id FROM postings GROUP BY transaction_id HAVING SUM(amount_minor) <> 0) broken;
