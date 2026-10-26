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

    /** Preserve the same Java UUID lock order used by the other strategies. */
    public java.util.List<AccountBalance> findPairForUpdate(UUID first, UUID second) {
        return jdbcClient.sql("""
            SELECT account_id,balance_minor,account_type,version FROM account_balances
            WHERE account_id IN (?,?)
            ORDER BY CASE WHEN account_id=? THEN 0 ELSE 1 END FOR UPDATE
            """).params(first,second,first).query(rowMapper).list();
    }

    public void updatePair(AccountBalance first, AccountBalance second) {
        int changed = jdbcClient.sql("""
            UPDATE account_balances b SET balance_minor=v.balance_minor,version=v.version
            FROM (VALUES(CAST(? AS uuid),CAST(? AS bigint),CAST(? AS bigint)),
                        (CAST(? AS uuid),CAST(? AS bigint),CAST(? AS bigint))) v(account_id,balance_minor,version)
            WHERE b.account_id=v.account_id
            """).params(first.accountId(),first.balanceMinor(),first.version(),
                second.accountId(),second.balanceMinor(),second.version()).update();
        if (changed != 2) throw new IllegalStateException("Both locked balances must be updated");
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
