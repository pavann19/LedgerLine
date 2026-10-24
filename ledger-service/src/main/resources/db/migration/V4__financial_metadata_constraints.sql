-- Additive migration: invalid existing metadata fails migration rather than being rewritten.
ALTER TABLE accounts ADD CONSTRAINT chk_accounts_type CHECK (type IN ('CUSTOMER', 'OVERDRAFT', 'INTERNAL'));
ALTER TABLE accounts ADD CONSTRAINT chk_accounts_status CHECK (status IN ('ACTIVE', 'SUSPENDED', 'CLOSED'));
ALTER TABLE accounts ADD CONSTRAINT chk_accounts_currency CHECK (currency ~ '^[A-Z]{3}$');
ALTER TABLE accounts ADD CONSTRAINT uq_accounts_id_type UNIQUE (id, type);
ALTER TABLE accounts ADD CONSTRAINT uq_accounts_id_currency UNIQUE (id, currency);
ALTER TABLE account_balances ADD CONSTRAINT fk_balance_account_type
    FOREIGN KEY (account_id, account_type) REFERENCES accounts(id, type);
ALTER TABLE postings ADD CONSTRAINT fk_posting_account_currency
    FOREIGN KEY (account_id, currency) REFERENCES accounts(id, currency);
ALTER TABLE postings ADD CONSTRAINT chk_postings_nonzero CHECK (amount_minor <> 0);
ALTER TABLE postings ADD CONSTRAINT uq_postings_transaction_account UNIQUE (transaction_id, account_id);
ALTER TABLE transactions ADD CONSTRAINT chk_transactions_status CHECK (status = 'POSTED');

-- Keep SUM as NUMERIC: PostgreSQL SUM(BIGINT) returns NUMERIC and must not narrow.
CREATE OR REPLACE FUNCTION verify_transaction_zero_sum() RETURNS TRIGGER AS $$
DECLARE
    total_sum NUMERIC;
    currencies INTEGER;
BEGIN
    SELECT COALESCE(SUM(amount_minor), 0), COUNT(DISTINCT currency)
    INTO total_sum, currencies FROM postings WHERE transaction_id = NEW.transaction_id;
    IF total_sum <> 0 OR currencies <> 1 THEN
        RAISE EXCEPTION 'Postings must balance within one currency';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE FUNCTION prevent_financial_history_modification() RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'Financial transaction and audit history is immutable';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_transactions_immutable BEFORE UPDATE OR DELETE ON transactions
FOR EACH ROW EXECUTE FUNCTION prevent_financial_history_modification();
CREATE TRIGGER trg_audit_log_immutable BEFORE UPDATE OR DELETE ON audit_log
FOR EACH ROW EXECUTE FUNCTION prevent_financial_history_modification();
