CREATE FUNCTION project_statement_batch(p_events jsonb) RETURNS integer LANGUAGE plpgsql AS $$
DECLARE
    accepted_events jsonb;
    changes jsonb;
    deltas jsonb;
    summary_day date;
    opening bigint;
    closing bigint;
    delta_in bigint;
    delta_out bigint;
    delta_count bigint;
    account uuid;
    change record;
    prior_balance bigint;
    day_start timestamptz;
    day_end timestamptz;
BEGIN
    IF jsonb_array_length(p_events)>100 THEN RAISE EXCEPTION 'Projection batch exceeds 100 events'; END IF;
    -- Mark in event-ID order, then take account locks in the existing text UUID order.
    WITH raw AS (
        SELECT value payload,ordinality position,(value->>'eventId')::uuid event_id
        FROM jsonb_array_elements(p_events) WITH ORDINALITY
    ), input AS (
        SELECT DISTINCT ON(event_id) * FROM raw ORDER BY event_id,position
    ), accepted AS (
        INSERT INTO processed_events(event_id,processed_at)
        SELECT event_id,clock_timestamp() FROM input ORDER BY event_id
        ON CONFLICT DO NOTHING RETURNING event_id
    ) SELECT coalesce(jsonb_agg(i.payload ORDER BY i.position),'[]'::jsonb)
      INTO accepted_events FROM input i JOIN accepted a USING(event_id);
    IF jsonb_array_length(accepted_events)=0 THEN RETURN 0; END IF;

    FOR account IN
        SELECT id FROM (
            SELECT (value->>'fromAccountId')::uuid id FROM jsonb_array_elements(accepted_events)
            UNION SELECT (value->>'toAccountId')::uuid FROM jsonb_array_elements(accepted_events)
        ) accounts ORDER BY id::text
    LOOP
        PERFORM pg_advisory_xact_lock(hashtextextended(account::text,0));
    END LOOP;

    WITH events AS (
        SELECT value,ordinality FROM jsonb_array_elements(accepted_events) WITH ORDINALITY
    ), inserted AS (
        INSERT INTO statement_view(account_id,transaction_id,amount_minor,running_balance_minor,currency,created_at)
        SELECT p.account_id,(e.value->>'transactionId')::uuid,p.amount,0,e.value->>'currency',
               (e.value->>'timestamp')::timestamptz
        FROM events e CROSS JOIN LATERAL (
            VALUES((e.value->>'fromAccountId')::uuid,-(e.value->>'amountMinor')::bigint,0),
                  ((e.value->>'toAccountId')::uuid,(e.value->>'amountMinor')::bigint,1)
        ) p(account_id,amount,side) ORDER BY e.ordinality,p.side
        RETURNING account_id,created_at,amount_minor
    ) SELECT
        (SELECT jsonb_agg(row_to_json(c)) FROM (
            SELECT account_id,min(created_at) first_time FROM inserted GROUP BY account_id) c),
        (SELECT jsonb_agg(row_to_json(d)) FROM (
            SELECT account_id,(created_at AT TIME ZONE 'UTC')::date summary_date,
                coalesce(sum(amount_minor) FILTER(WHERE amount_minor>0),0) inflow,
                coalesce(sum(-amount_minor) FILTER(WHERE amount_minor<0),0) outflow,count(*) postings
            FROM inserted GROUP BY 1,2) d)
      INTO changes,deltas;

    FOR change IN SELECT * FROM jsonb_to_recordset(changes) AS c(account_id uuid,first_time timestamptz)
    LOOP
        SELECT running_balance_minor INTO prior_balance FROM statement_view
        WHERE account_id=change.account_id AND created_at<change.first_time
        ORDER BY created_at DESC,id DESC LIMIT 1;
        prior_balance := coalesce(prior_balance,0);
        -- Repair the suffix once per account, not once per event in the poll.
        WITH expected AS (
            SELECT id,prior_balance+sum(amount_minor) OVER(ORDER BY created_at,id) balance
            FROM statement_view WHERE account_id=change.account_id AND created_at>=change.first_time
        ) UPDATE statement_view s SET running_balance_minor=e.balance
          FROM expected e WHERE s.id=e.id AND s.running_balance_minor<>e.balance;
        FOR summary_day IN
            SELECT summary_date FROM daily_account_summary
            WHERE account_id=change.account_id AND summary_date>=(change.first_time AT TIME ZONE 'UTC')::date
            UNION SELECT d.summary_date FROM jsonb_to_recordset(deltas) AS d(account_id uuid,summary_date date)
            WHERE d.account_id=change.account_id
        LOOP
            day_start := summary_day::timestamp AT TIME ZONE 'UTC';
            day_end := (summary_day+1)::timestamp AT TIME ZONE 'UTC';
            SELECT running_balance_minor-amount_minor INTO opening FROM statement_view
            WHERE account_id=change.account_id AND created_at>=day_start AND created_at<day_end
            ORDER BY created_at,id LIMIT 1;
            SELECT running_balance_minor INTO closing FROM statement_view
            WHERE account_id=change.account_id AND created_at>=day_start AND created_at<day_end
            ORDER BY created_at DESC,id DESC LIMIT 1;
            SELECT d.inflow,d.outflow,d.postings INTO delta_in,delta_out,delta_count
            FROM jsonb_to_recordset(deltas) AS d(account_id uuid,summary_date date,inflow bigint,outflow bigint,postings bigint)
            WHERE d.account_id=change.account_id AND d.summary_date=summary_day;
            INSERT INTO daily_account_summary(account_id,summary_date,opening_balance_minor,closing_balance_minor,
                total_inflow_minor,total_outflow_minor,posting_count)
            VALUES(change.account_id,summary_day,opening,closing,coalesce(delta_in,0),coalesce(delta_out,0),coalesce(delta_count,0))
            ON CONFLICT(account_id,summary_date) DO UPDATE SET
                opening_balance_minor=EXCLUDED.opening_balance_minor,closing_balance_minor=EXCLUDED.closing_balance_minor,
                total_inflow_minor=daily_account_summary.total_inflow_minor+EXCLUDED.total_inflow_minor,
                total_outflow_minor=daily_account_summary.total_outflow_minor+EXCLUDED.total_outflow_minor,
                posting_count=daily_account_summary.posting_count+EXCLUDED.posting_count;
        END LOOP;
    END LOOP;
    RETURN jsonb_array_length(accepted_events);
END;
$$;
