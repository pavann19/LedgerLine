ALTER TABLE accounts ADD COLUMN owner_principal TEXT NOT NULL DEFAULT 'LEGACY_SYSTEM';
CREATE INDEX idx_accounts_owner_principal ON accounts(owner_principal);

ALTER TABLE transactions ADD COLUMN principal_id TEXT NOT NULL DEFAULT 'LEGACY_SYSTEM';
ALTER TABLE transactions DROP CONSTRAINT transactions_idempotency_key_key;
ALTER TABLE transactions ADD CONSTRAINT uq_transactions_principal_idempotency
    UNIQUE (principal_id, idempotency_key);

ALTER TABLE audit_log ADD COLUMN principal_id TEXT;
UPDATE audit_log SET principal_id = actor WHERE principal_id IS NULL;
ALTER TABLE audit_log ALTER COLUMN principal_id SET NOT NULL;
