-- V2__triggers_and_constraints.sql
-- Invariant guarantees: Zero-sum deferred check and Postings immutability

-- 1. Deferred zero-sum constraint trigger on postings
CREATE OR REPLACE FUNCTION verify_transaction_zero_sum()
RETURNS TRIGGER AS $$
DECLARE
    total_sum BIGINT;
BEGIN
    SELECT COALESCE(SUM(amount_minor), 0)
    INTO total_sum
    FROM postings
    WHERE transaction_id = NEW.transaction_id;

    IF total_sum <> 0 THEN
        RAISE EXCEPTION 'Double-entry invariant violation: transaction % postings sum to %, expected 0',
            NEW.transaction_id, total_sum;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_postings_zero_sum ON postings;

CREATE CONSTRAINT TRIGGER trg_postings_zero_sum
AFTER INSERT OR UPDATE ON postings
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW
EXECUTE FUNCTION verify_transaction_zero_sum();

-- 2. Postings immutability trigger: prevent UPDATE or DELETE
CREATE OR REPLACE FUNCTION prevent_postings_modification()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'Postings are immutable. Updates and deletes are prohibited. Corrections must be reversing transactions.';
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_prevent_postings_modification ON postings;

CREATE TRIGGER trg_prevent_postings_modification
BEFORE UPDATE OR DELETE ON postings
FOR EACH ROW
EXECUTE FUNCTION prevent_postings_modification();
