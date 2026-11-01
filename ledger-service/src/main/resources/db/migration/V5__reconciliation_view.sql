-- Default view-owner permissions expose aggregates without granting base tables.
CREATE VIEW ledger_reconciliation WITH (security_barrier=true) AS
SELECT 1::integer schema_version,
 (SELECT count(*) FROM postings) ledger_postings,
 (SELECT count(*) FROM (SELECT transaction_id FROM postings GROUP BY transaction_id HAVING sum(amount_minor)<>0) x) unbalanced_transactions,
 (SELECT count(*) FROM account_balances b LEFT JOIN
    (SELECT account_id,sum(amount_minor) balance FROM postings GROUP BY account_id) p USING(account_id)
    WHERE b.balance_minor<>coalesce(p.balance,0)) balance_drift,
 (SELECT count(*) FROM account_balances WHERE account_type<>'OVERDRAFT' AND balance_minor<0) negative_protected,
 (SELECT count(*) FROM outbox WHERE published_at IS NULL) outbox_pending,
 (SELECT coalesce(extract(epoch FROM clock_timestamp()-min(created_at)),0)::double precision FROM outbox WHERE published_at IS NULL) outbox_oldest_seconds;
REVOKE ALL ON ledger_reconciliation FROM PUBLIC;
