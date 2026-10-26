package com.ledgerline.projection.repository;

import com.ledgerline.projection.model.DailyAccountSummary;
import com.ledgerline.projection.model.StatementViewEntry;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class ProjectionRepository {

    private final JdbcClient jdbcClient;

    private final RowMapper<StatementViewEntry> statementRowMapper = (rs, rowNum) -> new StatementViewEntry(
        rs.getLong("id"),
        rs.getObject("account_id", UUID.class),
        rs.getObject("transaction_id", UUID.class),
        rs.getLong("amount_minor"),
        rs.getLong("running_balance_minor"),
        rs.getString("currency"),
        rs.getTimestamp("created_at").toInstant()
    );

    private final RowMapper<DailyAccountSummary> summaryRowMapper = (rs, rowNum) -> new DailyAccountSummary(
        rs.getObject("account_id", UUID.class),
        rs.getDate("summary_date").toLocalDate(),
        rs.getLong("opening_balance_minor"),
        rs.getLong("closing_balance_minor"),
        rs.getLong("total_inflow_minor"),
        rs.getLong("total_outflow_minor"),
        rs.getInt("posting_count")
    );

    public ProjectionRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public void lockAccount(UUID accountId) {
        jdbcClient.sql("SELECT pg_advisory_xact_lock(hashtextextended(CAST(? AS text), 0))")
            .param(accountId)
            .query((rs, rowNum) -> 0)
            .single();
    }

    public int projectBatch(String payloads) {
        try {
            return jdbcClient.sql("SELECT project_statement_batch(CAST(? AS jsonb))")
                .param(payloads).query(Integer.class).single();
        } catch (org.springframework.dao.DataAccessException failure) {
            for (Throwable cause=failure; cause!=null; cause=cause.getCause()) {
                if (cause instanceof java.sql.SQLException sql && "22003".equals(sql.getSQLState())) {
                    var overflow=new ArithmeticException("Projection amount exceeds signed 64-bit range");
                    overflow.initCause(failure);
                    throw overflow;
                }
            }
            throw failure;
        }
    }

    /** Caller must hold the transaction-scoped account lock. Equal timestamps append by id. */
    public void insertAndProjectPosting(UUID accountId, UUID transactionId, long amountMinor,
        String currency, Instant createdAt) {
        try {
            jdbcClient.sql("SELECT project_statement_posting(?, ?, ?, ?, ?)")
                .params(accountId, transactionId, amountMinor, currency, Timestamp.from(createdAt))
                .query((rs, row) -> 0).single();
        } catch (org.springframework.dao.DataAccessException failure) {
            // Preserve the checked-money contract when PostgreSQL rejects BIGINT overflow.
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause instanceof java.sql.SQLException sql && "22003".equals(sql.getSQLState())) {
                    var overflow = new ArithmeticException("Projection amount exceeds signed 64-bit range");
                    overflow.initCause(failure);
                    throw overflow;
                }
            }
            throw failure;
        }
    }

    public List<StatementViewEntry> getStatement(UUID accountId, int limit, int offset) {
        String sql = """
            SELECT id, account_id, transaction_id, amount_minor, running_balance_minor, currency, created_at 
            FROM statement_view 
            WHERE account_id = ? 
            ORDER BY created_at DESC, id DESC
            LIMIT ? OFFSET ?
            """;
        return jdbcClient.sql(sql)
            .params(accountId, limit, offset)
            .query(statementRowMapper)
            .list();
    }

    public List<StatementViewEntry> getStatementPage(UUID accountId, int limit, Long beforeId) {
        if (beforeId == null) {
            return jdbcClient.sql("""
                SELECT id, account_id, transaction_id, amount_minor, running_balance_minor, currency, created_at
                FROM statement_view WHERE account_id = ? ORDER BY created_at DESC, id DESC LIMIT ?
                """).params(accountId, limit).query(statementRowMapper).list();
        }
        String sql = """
            SELECT id, account_id, transaction_id, amount_minor, running_balance_minor, currency, created_at
            FROM statement_view
            WHERE account_id = ?
              AND (created_at, id) < (
                  SELECT created_at, id FROM statement_view WHERE id = ? AND account_id = ?
              )
            ORDER BY created_at DESC, id DESC LIMIT ?
            """;
        return jdbcClient.sql(sql).params(accountId, beforeId, accountId, limit).query(statementRowMapper).list();
    }

    public Optional<DailyAccountSummary> getDailySummary(UUID accountId, LocalDate date) {
        String sql = """
            SELECT account_id, summary_date, opening_balance_minor, closing_balance_minor,
                   total_inflow_minor, total_outflow_minor, posting_count 
            FROM daily_account_summary 
            WHERE account_id = ? AND summary_date = ?
            """;
        return jdbcClient.sql(sql)
            .params(accountId, Date.valueOf(date))
            .query(summaryRowMapper)
            .optional();
    }
}
