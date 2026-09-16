package com.ledgerline.ledger.repository;

import com.ledgerline.ledger.domain.AccountBalance;
import com.ledgerline.ledger.domain.AccountType;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public class AccountBalanceRepository {

    private final JdbcClient jdbcClient;

    private final RowMapper<AccountBalance> rowMapper = (rs, rowNum) -> new AccountBalance(
        rs.getObject("account_id", UUID.class),
        rs.getLong("balance_minor"),
        AccountType.valueOf(rs.getString("account_type")),
        rs.getLong("version")
    );

    public AccountBalanceRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public void createBalance(AccountBalance balance) {
        String sql = """
            INSERT INTO account_balances (account_id, balance_minor, account_type, version)
            VALUES (?, ?, ?, ?)
            """;
        jdbcClient.sql(sql)
            .params(
                balance.accountId(),
                balance.balanceMinor(),
                balance.accountType().name(),
                balance.version()
            )
            .update();
    }

    public Optional<AccountBalance> findById(UUID accountId) {
        String sql = """
            SELECT account_id, balance_minor, account_type, version 
            FROM account_balances 
            WHERE account_id = ?
            """;
        return jdbcClient.sql(sql).param(accountId).query(rowMapper).optional();
    }

    public Optional<AccountBalance> findByIdForUpdate(UUID accountId) {
        String sql = """
            SELECT account_id, balance_minor, account_type, version 
            FROM account_balances 
            WHERE account_id = ? 
            FOR UPDATE
            """;
        return jdbcClient.sql(sql).param(accountId).query(rowMapper).optional();
    }

    public void updateBalance(AccountBalance balance) {
        String sql = """
            UPDATE account_balances 
            SET balance_minor = ?, version = ? 
            WHERE account_id = ?
            """;
        jdbcClient.sql(sql)
            .params(balance.balanceMinor(), balance.version(), balance.accountId())
            .update();
    }

    public int updateBalanceOptimistic(UUID accountId, long newBalance, long expectedVersion) {
        String sql = """
            UPDATE account_balances 
            SET balance_minor = ?, version = version + 1 
            WHERE account_id = ? AND version = ?
            """;
        return jdbcClient.sql(sql)
            .params(newBalance, accountId, expectedVersion)
            .update();
    }

    public void updateBalanceBroken(UUID accountId, long newBalance) {
        // Deliberately no version check and no lock (used for Variant 0)
        String sql = """
            UPDATE account_balances 
            SET balance_minor = ? 
            WHERE account_id = ?
            """;
        jdbcClient.sql(sql)
            .params(newBalance, accountId)
            .update();
    }
}
