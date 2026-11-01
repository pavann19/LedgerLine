-- Caller holds the transaction-scoped account advisory lock. This function and
-- processed_events execute in the same application transaction.
CREATE FUNCTION project_statement_posting(
    p_account uuid, p_transaction uuid, p_amount bigint,
    p_currency text, p_time timestamptz
) RETURNS void LANGUAGE plpgsql AS $$
DECLARE
    latest_time timestamptz;
    prior_balance bigint;
    new_balance bigint;
    day date := (p_time AT TIME ZONE 'UTC')::date;
    day_start timestamptz := day::timestamp AT TIME ZONE 'UTC';
    day_end timestamptz := (day + 1)::timestamp AT TIME ZONE 'UTC';
    opening bigint;
    closing bigint;
BEGIN
    SELECT created_at, running_balance_minor INTO latest_time, prior_balance
    FROM statement_view WHERE account_id = p_account
    ORDER BY created_at DESC, id DESC LIMIT 1;

    IF latest_time IS NULL OR p_time >= latest_time THEN
        prior_balance := coalesce(prior_balance, 0);
        new_balance := prior_balance + p_amount;
        INSERT INTO statement_view(account_id,transaction_id,amount_minor,running_balance_minor,currency,created_at)
        VALUES(p_account,p_transaction,p_amount,new_balance,p_currency,p_time);
        INSERT INTO daily_account_summary(account_id,summary_date,opening_balance_minor,closing_balance_minor,
            total_inflow_minor,total_outflow_minor,posting_count)
        VALUES(p_account,day,prior_balance,new_balance,greatest(p_amount,0),
            CASE WHEN p_amount<0 THEN -p_amount ELSE 0 END,1)
        ON CONFLICT(account_id,summary_date) DO UPDATE SET
            closing_balance_minor=EXCLUDED.closing_balance_minor,
            total_inflow_minor=daily_account_summary.total_inflow_minor+EXCLUDED.total_inflow_minor,
            total_outflow_minor=daily_account_summary.total_outflow_minor+EXCLUDED.total_outflow_minor,
            posting_count=daily_account_summary.posting_count+1;
        RETURN;
    END IF;

    SELECT running_balance_minor INTO prior_balance FROM statement_view
    WHERE account_id=p_account AND created_at<=p_time
    ORDER BY created_at DESC,id DESC LIMIT 1;
    new_balance := coalesce(prior_balance,0)+p_amount;
    INSERT INTO statement_view(account_id,transaction_id,amount_minor,running_balance_minor,currency,created_at)
    VALUES(p_account,p_transaction,p_amount,new_balance,p_currency,p_time);
    UPDATE statement_view SET running_balance_minor=running_balance_minor+p_amount
    WHERE account_id=p_account AND created_at>p_time;
    SELECT running_balance_minor-amount_minor INTO opening FROM statement_view
    WHERE account_id=p_account AND created_at>=day_start AND created_at<day_end
    ORDER BY created_at,id LIMIT 1;
    SELECT running_balance_minor INTO closing FROM statement_view
    WHERE account_id=p_account AND created_at>=day_start AND created_at<day_end
    ORDER BY created_at DESC,id DESC LIMIT 1;
    INSERT INTO daily_account_summary(account_id,summary_date,opening_balance_minor,closing_balance_minor,
        total_inflow_minor,total_outflow_minor,posting_count)
    VALUES(p_account,day,opening,closing,greatest(p_amount,0),
        CASE WHEN p_amount<0 THEN -p_amount ELSE 0 END,1)
    ON CONFLICT(account_id,summary_date) DO UPDATE SET
        opening_balance_minor=EXCLUDED.opening_balance_minor,
        closing_balance_minor=EXCLUDED.closing_balance_minor,
        total_inflow_minor=daily_account_summary.total_inflow_minor+EXCLUDED.total_inflow_minor,
        total_outflow_minor=daily_account_summary.total_outflow_minor+EXCLUDED.total_outflow_minor,
        posting_count=daily_account_summary.posting_count+1;
    UPDATE daily_account_summary SET opening_balance_minor=opening_balance_minor+p_amount,
        closing_balance_minor=closing_balance_minor+p_amount
    WHERE account_id=p_account AND summary_date>day;
END;
$$;
