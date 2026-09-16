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

    public long getLatestRunningBalance(UUID accountId) {
        String sql = """
            SELECT running_balance_minor 
            FROM statement_view 
            WHERE account_id = ? 
            ORDER BY id DESC 
            LIMIT 1
            """;
        return jdbcClient.sql(sql)
            .param(accountId)
            .query(Long.class)
            .optional()
            .orElse(0L);
    }

    public void insertStatementView(
        UUID accountId,
        UUID transactionId,
        long amountMinor,
        long runningBalanceMinor,
        String currency,
        Instant createdAt
    ) {
        String sql = """
            INSERT INTO statement_view (account_id, transaction_id, amount_minor, running_balance_minor, currency, created_at)
            VALUES (?, ?, ?, ?, ?, ?)
            """;
        jdbcClient.sql(sql)
            .params(
                accountId,
                transactionId,
                amountMinor,
                runningBalanceMinor,
                currency,
                Timestamp.from(createdAt)
            )
            .update();
    }

    public void upsertDailySummary(UUID accountId, LocalDate date, long amountDelta, long newRunningBalance) {
        long inflow = amountDelta > 0 ? amountDelta : 0L;
        long outflow = amountDelta < 0 ? Math.abs(amountDelta) : 0L;

        String sql = """
            INSERT INTO daily_account_summary (
                account_id, summary_date, opening_balance_minor, closing_balance_minor,
                total_inflow_minor, total_outflow_minor, posting_count
            )
            VALUES (?, ?, ?, ?, ?, ?, 1)
            ON CONFLICT (account_id, summary_date) DO UPDATE SET
                closing_balance_minor = EXCLUDED.closing_balance_minor,
                total_inflow_minor = daily_account_summary.total_inflow_minor + EXCLUDED.total_inflow_minor,
                total_outflow_minor = daily_account_summary.total_outflow_minor + EXCLUDED.total_outflow_minor,
                posting_count = daily_account_summary.posting_count + 1
            """;

        jdbcClient.sql(sql)
            .params(
                accountId,
                Date.valueOf(date),
                newRunningBalance - amountDelta, // opening balance for initial insert
                newRunningBalance,
                inflow,
                outflow
            )
            .update();
    }

    public List<StatementViewEntry> getStatement(UUID accountId, int limit, int offset) {
        String sql = """
            SELECT id, account_id, transaction_id, amount_minor, running_balance_minor, currency, created_at 
            FROM statement_view 
            WHERE account_id = ? 
            ORDER BY id DESC 
            LIMIT ? OFFSET ?
            """;
        return jdbcClient.sql(sql)
            .params(accountId, limit, offset)
            .query(statementRowMapper)
            .list();
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
