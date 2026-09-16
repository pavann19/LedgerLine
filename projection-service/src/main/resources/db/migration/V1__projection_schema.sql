-- V1__projection_schema.sql
-- Read-model tables for Statement View, Daily Account Summary, and Event Deduplication

CREATE TABLE IF NOT EXISTS processed_events (
    event_id UUID PRIMARY KEY,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

CREATE TABLE IF NOT EXISTS statement_view (
    id BIGSERIAL PRIMARY KEY,
    account_id UUID NOT NULL,
    transaction_id UUID NOT NULL,
    amount_minor BIGINT NOT NULL,
    running_balance_minor BIGINT NOT NULL,
    currency CHAR(3) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

CREATE INDEX IF NOT EXISTS idx_statement_account_date 
    ON statement_view(account_id, created_at DESC);

CREATE TABLE IF NOT EXISTS daily_account_summary (
    account_id UUID NOT NULL,
    summary_date DATE NOT NULL,
    opening_balance_minor BIGINT NOT NULL,
    closing_balance_minor BIGINT NOT NULL,
    total_inflow_minor BIGINT NOT NULL,
    total_outflow_minor BIGINT NOT NULL,
    posting_count INT NOT NULL DEFAULT 0,
    PRIMARY KEY (account_id, summary_date)
);
