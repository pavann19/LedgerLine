package com.ledgerline.ledger.repository;

import com.ledgerline.ledger.domain.Posting;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;

@Repository
public class PostingRepository {

    private final JdbcClient jdbcClient;

    private final RowMapper<Posting> rowMapper = (rs, rowNum) -> new Posting(
        rs.getLong("id"),
        rs.getObject("transaction_id", UUID.class),
        rs.getObject("account_id", UUID.class),
        rs.getLong("amount_minor"),
        rs.getString("currency"),
        rs.getTimestamp("created_at").toInstant()
    );

    public PostingRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public void insertPosting(Posting posting) {
        String sql = """
            INSERT INTO postings (transaction_id, account_id, amount_minor, currency, created_at)
            VALUES (?, ?, ?, ?, ?)
            """;
        jdbcClient.sql(sql)
            .params(
                posting.transactionId(),
                posting.accountId(),
                posting.amountMinor(),
                posting.currency(),
                Timestamp.from(posting.createdAt())
            )
            .update();
    }

    public List<Posting> findByAccountId(UUID accountId, int limit, int offset) {
        String sql = """
            SELECT id, transaction_id, account_id, amount_minor, currency, created_at 
            FROM postings 
            WHERE account_id = ? 
            ORDER BY id DESC 
            LIMIT ? OFFSET ?
            """;
        return jdbcClient.sql(sql)
            .params(accountId, limit, offset)
            .query(rowMapper)
            .list();
    }

    public List<Posting> findByTransactionId(UUID transactionId) {
        String sql = """
            SELECT id, transaction_id, account_id, amount_minor, currency, created_at 
            FROM postings 
            WHERE transaction_id = ? 
            ORDER BY id ASC
            """;
        return jdbcClient.sql(sql)
            .param(transactionId)
            .query(rowMapper)
            .list();
    }

    public long getSumByAccountId(UUID accountId) {
        String sql = "SELECT COALESCE(SUM(amount_minor), 0) FROM postings WHERE account_id = ?";
        Long sum = jdbcClient.sql(sql).param(accountId).query(Long.class).single();
        return sum != null ? sum : 0L;
    }
}
