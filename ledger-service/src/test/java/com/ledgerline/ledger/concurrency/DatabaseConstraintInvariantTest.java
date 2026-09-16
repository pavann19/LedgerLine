package com.ledgerline.ledger.concurrency;

import com.ledgerline.ledger.domain.AccountStatus;
import com.ledgerline.ledger.domain.AccountType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class DatabaseConstraintInvariantTest extends BaseIntegrationTest {

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    @DisplayName("Deferred constraint trigger: rejects unbalanced postings at transaction commit")
    void shouldRejectUnbalancedPostingsAtCommit() {
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);

        UUID accountId = createTestAccount(AccountType.CUSTOMER);
        UUID txId = UUID.randomUUID();

        // 1. Create transaction header
        jdbcClient.sql("INSERT INTO transactions (id, idempotency_key, request_hash, status) VALUES (?, ?, ?, ?)")
            .params(txId, "unbalanced-test-" + txId, new byte[]{1, 2, 3}, "POSTED")
            .update();

        // 2. Attempt single posting (-1000) inside transaction: commit must fail due to deferred trigger
        assertThrows(Exception.class, () -> {
            txTemplate.execute(status -> {
                jdbcClient.sql("""
                    INSERT INTO postings (transaction_id, account_id, amount_minor, currency, created_at)
                    VALUES (?, ?, ?, ?, ?)
                    """)
                    .params(txId, accountId, -1000L, "USD", Timestamp.from(Instant.now()))
                    .update();
                return null; // Triggers commit, which fires deferred constraint trigger
            });
        });

        // Verify that postings was not persisted
        Integer postingCount = jdbcClient.sql("SELECT COUNT(1) FROM postings WHERE transaction_id = ?")
            .param(txId)
            .query(Integer.class)
            .single();
        assertEquals(0, postingCount, "Unbalanced posting must be rolled back");
    }

    @Test
    @DisplayName("Deferred constraint trigger: accepts perfectly balanced postings at commit")
    void shouldAcceptBalancedPostingsAtCommit() {
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);

        UUID accountA = createTestAccount(AccountType.CUSTOMER);
        UUID accountB = createTestAccount(AccountType.CUSTOMER);
        UUID txId = UUID.randomUUID();

        jdbcClient.sql("INSERT INTO transactions (id, idempotency_key, request_hash, status) VALUES (?, ?, ?, ?)")
            .params(txId, "balanced-test-" + txId, new byte[]{4, 5, 6}, "POSTED")
            .update();

        assertDoesNotThrow(() -> {
            txTemplate.execute(status -> {
                jdbcClient.sql("""
                    INSERT INTO postings (transaction_id, account_id, amount_minor, currency, created_at)
                    VALUES (?, ?, ?, ?, ?)
                    """)
                    .params(txId, accountA, -5000L, "USD", Timestamp.from(Instant.now()))
                    .update();

                jdbcClient.sql("""
                    INSERT INTO postings (transaction_id, account_id, amount_minor, currency, created_at)
                    VALUES (?, ?, ?, ?, ?)
                    """)
                    .params(txId, accountB, 5000L, "USD", Timestamp.from(Instant.now()))
                    .update();
                return null;
            });
        });

        Integer postingCount = jdbcClient.sql("SELECT COUNT(1) FROM postings WHERE transaction_id = ?")
            .param(txId)
            .query(Integer.class)
            .single();
        assertEquals(2, postingCount, "Both balanced postings must be committed");
    }

    @Test
    @DisplayName("Check constraint: rejects negative balance for CUSTOMER accounts")
    void shouldRejectNegativeBalanceForCustomerAccount() {
        UUID accountId = createTestAccount(AccountType.CUSTOMER);

        assertThrows(DataIntegrityViolationException.class, () -> {
            jdbcClient.sql("UPDATE account_balances SET balance_minor = -100 WHERE account_id = ?")
                .param(accountId)
                .update();
        });
    }

    @Test
    @DisplayName("Check constraint: allows negative balance for OVERDRAFT accounts")
    void shouldAllowNegativeBalanceForOverdraftAccount() {
        UUID accountId = createTestAccount(AccountType.OVERDRAFT);

        assertDoesNotThrow(() -> {
            jdbcClient.sql("UPDATE account_balances SET balance_minor = -5000 WHERE account_id = ?")
                .param(accountId)
                .update();
        });

        Long balance = jdbcClient.sql("SELECT balance_minor FROM account_balances WHERE account_id = ?")
            .param(accountId)
            .query(Long.class)
            .single();
        assertEquals(-5000L, balance);
    }

    @Test
    @DisplayName("Immutability trigger: prevents UPDATE or DELETE on postings")
    void shouldPreventUpdateOrDeleteOnPostings() {
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);

        UUID accountA = createTestAccount(AccountType.CUSTOMER);
        UUID accountB = createTestAccount(AccountType.CUSTOMER);
        UUID txId = UUID.randomUUID();

        jdbcClient.sql("INSERT INTO transactions (id, idempotency_key, request_hash, status) VALUES (?, ?, ?, ?)")
            .params(txId, "immutable-test-" + txId, new byte[]{7, 8, 9}, "POSTED")
            .update();

        txTemplate.execute(status -> {
            jdbcClient.sql("INSERT INTO postings (transaction_id, account_id, amount_minor, currency) VALUES (?, ?, ?, ?)")
                .params(txId, accountA, -100L, "USD").update();
            jdbcClient.sql("INSERT INTO postings (transaction_id, account_id, amount_minor, currency) VALUES (?, ?, ?, ?)")
                .params(txId, accountB, 100L, "USD").update();
            return null;
        });

        // Try UPDATE
        assertThrows(Exception.class, () -> {
            jdbcClient.sql("UPDATE postings SET amount_minor = 999 WHERE transaction_id = ?")
                .param(txId)
                .update();
        });

        // Try DELETE
        assertThrows(Exception.class, () -> {
            jdbcClient.sql("DELETE FROM postings WHERE transaction_id = ?")
                .param(txId)
                .update();
        });
    }

    private UUID createTestAccount(AccountType type) {
        UUID id = UUID.randomUUID();
        jdbcClient.sql("INSERT INTO accounts (id, currency, type, status, created_at, version) VALUES (?, ?, ?, ?, ?, ?)")
            .params(id, "USD", type.name(), AccountStatus.ACTIVE.name(), Timestamp.from(Instant.now()), 0L)
            .update();
        jdbcClient.sql("INSERT INTO account_balances (account_id, balance_minor, account_type, version) VALUES (?, ?, ?, ?)")
            .params(id, 0L, type.name(), 0L)
            .update();
        return id;
    }
}
