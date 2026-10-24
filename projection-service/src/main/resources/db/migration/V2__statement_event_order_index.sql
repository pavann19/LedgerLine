-- Supports predecessor lookup and bounded repairs for late events.
CREATE INDEX idx_statement_account_event_order
    ON statement_view (account_id, created_at, id);
