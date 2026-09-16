package com.ledgerline.ledger.repository;

import com.ledgerline.ledger.domain.Account;
import com.ledgerline.ledger.domain.AccountStatus;
import com.ledgerline.ledger.domain.AccountType;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;

@Repository
public class AccountRepository {

    private final JdbcClient jdbcClient;

    private final RowMapper<Account> rowMapper = (rs, rowNum) -> new Account(
        rs.getObject("id", UUID.class),
        rs.getString("currency"),
        AccountType.valueOf(rs.getString("type")),
        AccountStatus.valueOf(rs.getString("status")),
        rs.getTimestamp("created_at").toInstant(),
        rs.getLong("version")
    );

    public AccountRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public void createAccount(Account account) {
        String sql = """
            INSERT INTO accounts (id, currency, type, status, created_at, version)
            VALUES (?, ?, ?, ?, ?, ?)
            """;
        jdbcClient.sql(sql)
            .params(
                account.id(),
                account.currency(),
                account.type().name(),
                account.status().name(),
                Timestamp.from(account.createdAt()),
                account.version()
            )
            .update();
    }

    public Optional<Account> findById(UUID id) {
        String sql = "SELECT id, currency, type, status, created_at, version FROM accounts WHERE id = ?";
        return jdbcClient.sql(sql).param(id).query(rowMapper).optional();
    }

    public boolean existsById(UUID id) {
        String sql = "SELECT COUNT(1) FROM accounts WHERE id = ?";
        Integer count = jdbcClient.sql(sql).param(id).query(Integer.class).single();
        return count != null && count > 0;
    }
}
