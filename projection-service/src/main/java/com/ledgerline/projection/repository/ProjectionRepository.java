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

    private void insertStatementView(
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

    /** Caller must hold the transaction-scoped account lock. Equal timestamps append by id. */
    public void insertAndProjectPosting(UUID accountId, UUID transactionId, long amountMinor,
        String currency, Instant createdAt) {
        Timestamp time = Timestamp.from(createdAt);
        long precedingBalance = jdbcClient.sql("""
            SELECT running_balance_minor FROM statement_view
            WHERE account_id = ? AND created_at <= ?
            ORDER BY created_at DESC, id DESC LIMIT 1
            """).params(accountId, time).query(Long.class).optional().orElse(0L);
        long runningBalance = Math.addExact(precedingBalance, amountMinor);
        insertStatementView(accountId, transactionId, amountMinor, runningBalance, currency, createdAt);

        // In-order events touch no old rows. A late event repairs only its suffix.
        jdbcClient.sql("""
            UPDATE statement_view SET running_balance_minor = running_balance_minor + ?
            WHERE account_id = ? AND created_at > ?
            """).params(amountMinor, accountId, time).update();

        // PostgreSQL rounds timestamps to microseconds; use its persisted day at midnight boundaries.
        LocalDate day = jdbcClient.sql("SELECT (CAST(? AS timestamptz) AT TIME ZONE 'UTC')::date")
            .param(time).query(Date.class).single().toLocalDate();
        Timestamp start = Timestamp.from(day.atStartOfDay(java.time.ZoneOffset.UTC).toInstant());
        Timestamp end = Timestamp.from(day.plusDays(1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant());
        long opening = jdbcClient.sql("""
            SELECT running_balance_minor - amount_minor FROM statement_view
            WHERE account_id = ? AND created_at >= ? AND created_at < ?
            ORDER BY created_at, id LIMIT 1
            """).params(accountId, start, end).query(Long.class).single();
        long closing = jdbcClient.sql("""
            SELECT running_balance_minor FROM statement_view
            WHERE account_id = ? AND created_at >= ? AND created_at < ?
            ORDER BY created_at DESC, id DESC LIMIT 1
            """).params(accountId, start, end).query(Long.class).single();
        jdbcClient.sql("""
            INSERT INTO daily_account_summary
                (account_id, summary_date, opening_balance_minor, closing_balance_minor,
                 total_inflow_minor, total_outflow_minor, posting_count)
            VALUES (?, ?, ?, ?, ?, ?, 1)
            ON CONFLICT (account_id, summary_date) DO UPDATE SET
                opening_balance_minor = EXCLUDED.opening_balance_minor,
                closing_balance_minor = EXCLUDED.closing_balance_minor,
                total_inflow_minor = daily_account_summary.total_inflow_minor + EXCLUDED.total_inflow_minor,
                total_outflow_minor = daily_account_summary.total_outflow_minor + EXCLUDED.total_outflow_minor,
                posting_count = daily_account_summary.posting_count + 1
            """).params(accountId, Date.valueOf(day), opening, closing,
                Math.max(amountMinor, 0), amountMinor < 0 ? Math.negateExact(amountMinor) : 0).update();
        jdbcClient.sql("""
            UPDATE daily_account_summary
            SET opening_balance_minor = opening_balance_minor + ?, closing_balance_minor = closing_balance_minor + ?
            WHERE account_id = ? AND summary_date > ?
            """).params(amountMinor, amountMinor, accountId, Date.valueOf(day)).update();
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
