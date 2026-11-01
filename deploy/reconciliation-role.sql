-- Operator-only provisioning, after BOTH services migrate the isolated database.
-- No login or password is created by a migration or a runtime agent.
DO $$ BEGIN
 IF NOT EXISTS(SELECT FROM pg_roles WHERE rolname='ledgerline_reconciliation_reader') THEN
  CREATE ROLE ledgerline_reconciliation_reader NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION;
 END IF;
END $$;
GRANT USAGE ON SCHEMA public TO ledgerline_reconciliation_reader;
GRANT SELECT ON ledger_reconciliation,projection_reconciliation TO ledgerline_reconciliation_reader;
-- Separately create a dedicated LOGIN with CONNECTION LIMIT 2, statement_timeout
-- 2000ms, default_transaction_read_only=on and this role. Never reuse the app user.
-- Do not grant base tables, sequence access, schema CREATE or grant options.
