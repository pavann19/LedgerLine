package com.ledgerline.ledger.repository;

import com.ledgerline.ledger.domain.Posting;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Journal, event and audit are one SQL command inside the caller's transaction. */
@Repository
public class TransferWriteRepository {
    private final JdbcClient jdbc;
    public TransferWriteRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public List<Posting> insert(UUID transaction, String scopedKey, byte[] hash,
        UUID from, UUID to, long amount, String currency, Instant time, String payload,
        String actor, String details) {
        int separator = scopedKey.indexOf('\u001F');
        String principal = separator < 0 ? "LEGACY_SYSTEM" : scopedKey.substring(0, separator);
        String key = separator < 0 ? scopedKey : scopedKey.substring(separator + 1);
        Timestamp timestamp = Timestamp.from(time);
        return jdbc.sql("""
            WITH new_tx AS (
                INSERT INTO transactions(id,principal_id,idempotency_key,request_hash,status,created_at)
                VALUES(?,?,?,?, 'POSTED',?) RETURNING id
            ), journal AS (
                INSERT INTO postings(transaction_id,account_id,amount_minor,currency,created_at)
                SELECT t.id,p.account_id,p.amount_minor,?,? FROM new_tx t
                CROSS JOIN (VALUES(CAST(? AS uuid),CAST(? AS bigint),0),
                                  (CAST(? AS uuid),CAST(? AS bigint),1)) p(account_id,amount_minor,position)
                ORDER BY p.position
                RETURNING id,transaction_id,account_id,amount_minor,currency,created_at
            ), event AS (
                INSERT INTO outbox(aggregate_id,event_type,payload,created_at,published_at)
                SELECT id,'TransferPosted',CAST(? AS jsonb),?,NULL FROM new_tx
            ), audit AS (
                INSERT INTO audit_log(actor,principal_id,action,entity_id,at,details)
                SELECT ?,?,'TRANSFER_POSTED',CAST(id AS text),?,CAST(? AS jsonb) FROM new_tx
            ) SELECT * FROM journal ORDER BY id
            """).params(transaction, principal, key, hash, timestamp, currency, timestamp,
                from, Math.negateExact(amount), to, amount, payload, timestamp, actor, actor, timestamp, details)
            .query((rs, row) -> new Posting(rs.getLong("id"), rs.getObject("transaction_id",UUID.class),
                rs.getObject("account_id",UUID.class), rs.getLong("amount_minor"),rs.getString("currency"),
                rs.getTimestamp("created_at").toInstant())).list();
    }
}
