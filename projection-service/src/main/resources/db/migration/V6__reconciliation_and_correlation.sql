CREATE TABLE projection_trace (
 event_id uuid PRIMARY KEY REFERENCES processed_events(event_id),
 transaction_id uuid NOT NULL,
 correlation_id varchar(64) NOT NULL,
 projected_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
ALTER FUNCTION project_statement_batch(jsonb) RENAME TO project_statement_batch_v4;
CREATE FUNCTION project_statement_batch(p_events jsonb) RETURNS integer LANGUAGE plpgsql AS $$
DECLARE accepted integer;
BEGIN
 accepted := project_statement_batch_v4(p_events);
 INSERT INTO projection_trace(event_id,transaction_id,correlation_id)
 SELECT (value->>'eventId')::uuid,(value->>'transactionId')::uuid,
        coalesce(value->>'correlationId',value->>'transactionId')
 FROM jsonb_array_elements(p_events) ON CONFLICT DO NOTHING;
 RETURN accepted;
END;
$$;
CREATE VIEW projection_reconciliation WITH (security_barrier=true) AS
SELECT 1::integer schema_version,
 (SELECT count(*) FROM statement_view) projection_postings,
 (SELECT count(*) FROM processed_events) processed_events,
 (SELECT count(*) FROM projection_quarantine) quarantine_pending,
 (SELECT count(*) FROM (
    SELECT running_balance_minor,sum(amount_minor) OVER(PARTITION BY account_id ORDER BY created_at,id) expected
    FROM statement_view) s WHERE running_balance_minor<>expected) statement_drift,
 (WITH expected AS (
    SELECT account_id,(created_at AT TIME ZONE 'UTC')::date summary_date,
      (array_agg(running_balance_minor-amount_minor ORDER BY created_at,id))[1] opening,
      (array_agg(running_balance_minor ORDER BY created_at DESC,id DESC))[1] closing,
      coalesce(sum(amount_minor) FILTER(WHERE amount_minor>0),0) inflow,
      coalesce(sum(-amount_minor) FILTER(WHERE amount_minor<0),0) outflow,count(*) postings
    FROM statement_view GROUP BY 1,2)
  SELECT count(*) FROM expected e FULL JOIN daily_account_summary d USING(account_id,summary_date)
  WHERE e.account_id IS NULL OR d.account_id IS NULL OR e.opening<>d.opening_balance_minor
    OR e.closing<>d.closing_balance_minor OR e.inflow<>d.total_inflow_minor
    OR e.outflow<>d.total_outflow_minor OR e.postings<>d.posting_count) summary_drift;
REVOKE ALL ON projection_reconciliation FROM PUBLIC;
