package com.ledgerline.ledger.repository;

import com.ledgerline.ledger.domain.Transaction;
import com.ledgerline.ledger.domain.TransactionStatus;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;

@Repository
public class TransactionRepository {

    private static final char PRINCIPAL_KEY_SEPARATOR = '\u001F';

    private final JdbcClient jdbcClient;

    private final RowMapper<Transaction> rowMapper = (rs, rowNum) -> new Transaction(
        rs.getObject("id", UUID.class),
        rs.getString("idempotency_key"),
        rs.getBytes("request_hash"),
        TransactionStatus.valueOf(rs.getString("status")),
        rs.getTimestamp("created_at").toInstant()
    );

    public TransactionRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public void insertTransaction(Transaction transaction) {
        ScopedIdempotencyKey scopedKey = splitScopedKey(transaction.idempotencyKey());
        String sql = """
            INSERT INTO transactions (id, principal_id, idempotency_key, request_hash, status, created_at)
            VALUES (?, ?, ?, ?, ?, ?)
            """;
        jdbcClient.sql(sql)
            .params(
                transaction.id(),
                scopedKey.principalId(),
                scopedKey.key(),
                transaction.requestHash(),
                transaction.status().name(),
                Timestamp.from(transaction.createdAt())
            )
            .update();
    }

    public Optional<Transaction> findByIdempotencyKey(String idempotencyKey) {
        ScopedIdempotencyKey scopedKey = splitScopedKey(idempotencyKey);
        String sql = """
            SELECT id, idempotency_key, request_hash, status, created_at 
            FROM transactions 
            WHERE principal_id = ? AND idempotency_key = ?
            """;
        return jdbcClient.sql(sql)
            .params(scopedKey.principalId(), scopedKey.key())
            .query(rowMapper)
            .optional();
    }

    public Optional<Transaction> findById(UUID id) {
        String sql = """
            SELECT id, idempotency_key, request_hash, status, created_at 
            FROM transactions 
            WHERE id = ?
            """;
        return jdbcClient.sql(sql).param(id).query(rowMapper).optional();
    }

    private ScopedIdempotencyKey splitScopedKey(String value) {
        int separator = value.indexOf(PRINCIPAL_KEY_SEPARATOR);
        if (separator < 0) {
            return new ScopedIdempotencyKey("LEGACY_SYSTEM", value);
        }
        return new ScopedIdempotencyKey(value.substring(0, separator), value.substring(separator + 1));
    }

    private record ScopedIdempotencyKey(String principalId, String key) {}
}
