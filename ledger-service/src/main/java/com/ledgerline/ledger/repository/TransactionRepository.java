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
        String sql = """
            INSERT INTO transactions (id, idempotency_key, request_hash, status, created_at)
            VALUES (?, ?, ?, ?, ?)
            """;
        jdbcClient.sql(sql)
            .params(
                transaction.id(),
                transaction.idempotencyKey(),
                transaction.requestHash(),
                transaction.status().name(),
                Timestamp.from(transaction.createdAt())
            )
            .update();
    }

    public Optional<Transaction> findByIdempotencyKey(String idempotencyKey) {
        String sql = """
            SELECT id, idempotency_key, request_hash, status, created_at 
            FROM transactions 
            WHERE idempotency_key = ?
            """;
        return jdbcClient.sql(sql).param(idempotencyKey).query(rowMapper).optional();
    }

    public Optional<Transaction> findById(UUID id) {
        String sql = """
            SELECT id, idempotency_key, request_hash, status, created_at 
            FROM transactions 
            WHERE id = ?
            """;
        return jdbcClient.sql(sql).param(id).query(rowMapper).optional();
    }
}
