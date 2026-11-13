package com.ledgerline.ledger.repository;

import com.ledgerline.ledger.domain.Posting;
import com.ledgerline.ledger.domain.AccountBalance;
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
        return insert(transaction, scopedKey, hash, from, to, amount, currency, time, payload, actor, details, List.of());
    }

    /** Caller holds both balance locks; all journal/cache writes share its transaction. */
    public List<Posting> insert(UUID transaction, String scopedKey, byte[] hash,
        UUID from, UUID to, long amount, String currency, Instant time, String payload,
        String actor, String details, List<AccountBalance> balances) {
        if (!balances.isEmpty() && (balances.size() != 2
            || !balances.get(0).accountId().equals(from) || !balances.get(1).accountId().equals(to)
            || from.equals(to))) throw new IllegalArgumentException("Expected source and destination balances");
        int separator = scopedKey.indexOf('\u001F');
        String principal = separator < 0 ? "LEGACY_SYSTEM" : scopedKey.substring(0, separator);
        String key = separator < 0 ? scopedKey : scopedKey.substring(separator + 1);
        Timestamp timestamp = Timestamp.from(time);
        String sql = """
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
            )
            """;
        var params = new java.util.ArrayList<Object>(List.of(transaction, principal, key, hash, timestamp,
            currency, timestamp, from, Math.negateExact(amount), to, amount, payload, timestamp,
            actor, actor, timestamp, details));
        if (!balances.isEmpty()) {
            sql += """
                , updated_balances AS (
                    UPDATE account_balances b SET balance_minor=v.balance_minor,version=v.version
                    FROM (VALUES(CAST(? AS uuid),CAST(? AS bigint),CAST(? AS bigint)),
                                (CAST(? AS uuid),CAST(? AS bigint),CAST(? AS bigint))) v(account_id,balance_minor,version)
                    WHERE b.account_id=v.account_id RETURNING b.account_id
                ) SELECT journal.*, (SELECT count(*) FROM updated_balances) AS updated_count FROM journal ORDER BY id
                """;
            for (AccountBalance balance : balances) {
                params.add(balance.accountId()); params.add(balance.balanceMinor()); params.add(balance.version());
            }
        } else sql += "SELECT * FROM journal ORDER BY id";
        return jdbc.sql(sql).params(params.toArray())
            .query((rs, row) -> {
                if (!balances.isEmpty() && rs.getInt("updated_count") != 2)
                    throw new IllegalStateException("Both locked balances must be updated");
                return new Posting(rs.getLong("id"), rs.getObject("transaction_id",UUID.class),
                rs.getObject("account_id",UUID.class), rs.getLong("amount_minor"),rs.getString("currency"),
                rs.getTimestamp("created_at").toInstant());
            }).list();
    }
}
