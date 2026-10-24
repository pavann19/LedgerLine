package com.ledgerline.ledger.concurrency;

import com.ledgerline.ledger.domain.*;
import com.ledgerline.ledger.dto.*;
import com.ledgerline.ledger.exception.InsufficientFundsException;
import com.ledgerline.ledger.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import java.util.UUID;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class FinancialEdgeRegressionTest extends BaseIntegrationTest {
    @Autowired AccountService accounts;
    @Autowired TransferService transfers;
    @Autowired JdbcClient jdbc;
    @Autowired org.springframework.transaction.PlatformTransactionManager manager;

    private UUID account(AccountType type, long balance) {
        UUID id = accounts.createAccount(new CreateAccountRequest("USD", type)).id();
        jdbc.sql("UPDATE account_balances SET balance_minor = ? WHERE account_id = ?").params(balance, id).update();
        return id;
    }

    @Test void overflowRollsBackEveryFinancialWriteInAllSafeStrategies() {
        for (IsolationVariant variant : new IsolationVariant[]{IsolationVariant.VARIANT_1_PESSIMISTIC,
                IsolationVariant.VARIANT_2_OPTIMISTIC, IsolationVariant.VARIANT_3_SERIALIZABLE}) {
            UUID from = account(AccountType.OVERDRAFT, 0), to = account(AccountType.OVERDRAFT, Long.MAX_VALUE);
            String key = "overflow-" + UUID.randomUUID();
            assertThrows(ArithmeticException.class, () -> transfers.transfer(key,
                new TransferRequest(from, to, 1, "USD"), variant));
            assertEquals(0, accounts.getAccount(from).balanceMinor());
            assertEquals(Long.MAX_VALUE, accounts.getAccount(to).balanceMinor());
            assertEquals(0, jdbc.sql("SELECT count(*) FROM transactions WHERE idempotency_key = ?").param(key).query(Integer.class).single());
            assertEquals(0, jdbc.sql("SELECT count(*) FROM postings WHERE account_id IN (?, ?)").params(from, to).query(Integer.class).single());
            assertEquals(0, jdbc.sql("SELECT count(*) FROM outbox WHERE payload->>'fromAccountId' = ?").param(from.toString()).query(Integer.class).single());
            UUID underflow = account(AccountType.OVERDRAFT, Long.MIN_VALUE);
            assertThrows(ArithmeticException.class, () -> transfers.transfer("underflow-" + UUID.randomUUID(),
                new TransferRequest(underflow, from, 1, "USD"), variant));
            assertEquals(Long.MIN_VALUE, accounts.getAccount(underflow).balanceMinor());
        }
    }

    @Test void databaseRejectsTypeMismatchAndHistoricalMutation() {
        UUID from = account(AccountType.CUSTOMER, 1), to = account(AccountType.CUSTOMER, 0);
        assertThrows(Exception.class, () -> jdbc.sql("UPDATE account_balances SET account_type = 'OVERDRAFT' WHERE account_id = ?").param(from).update());
        TransferResponse response = transfers.transfer("immutable-" + UUID.randomUUID(), new TransferRequest(from, to, 1, "USD"), null);
        assertThrows(Exception.class, () -> jdbc.sql("INSERT INTO postings (transaction_id,account_id,amount_minor,currency) VALUES (?,?,-1,'USD')")
            .params(response.transactionId(), from).update());
        assertThrows(Exception.class, () -> jdbc.sql("INSERT INTO postings (transaction_id,account_id,amount_minor,currency) VALUES (?,?,1,'EUR')")
            .params(UUID.randomUUID(), to).update());
        assertThrows(Exception.class, () -> jdbc.sql("UPDATE transactions SET request_hash = ? WHERE id = ?").params(new byte[]{1}, response.transactionId()).update());
        assertThrows(Exception.class, () -> jdbc.sql("DELETE FROM transactions WHERE id = ?").param(response.transactionId()).update());
        assertThrows(Exception.class, () -> jdbc.sql("UPDATE audit_log SET actor = 'forged' WHERE entity_id = ?").param(response.transactionId().toString()).update());
        assertThrows(Exception.class, () -> jdbc.sql("DELETE FROM audit_log WHERE entity_id = ?").param(response.transactionId().toString()).update());
    }

    @Test void metadataLocksRejectTransferAfterConcurrentClosureCommits() throws Exception {
        UUID from = account(AccountType.CUSTOMER, 1), to = account(AccountType.CUSTOMER, 0);
        CountDownLatch updated = new CountDownLatch(1), release = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger writerPid = new java.util.concurrent.atomic.AtomicInteger();
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<?> closure = pool.submit(() -> new org.springframework.transaction.support.TransactionTemplate(manager).execute(status -> {
                writerPid.set(jdbc.sql("SELECT pg_backend_pid()").query(Integer.class).single());
                jdbc.sql("UPDATE accounts SET status = 'CLOSED' WHERE id = ?").param(from).update();
                updated.countDown();
                try { assertTrue(release.await(10, TimeUnit.SECONDS)); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
                return null;
            }));
            assertTrue(updated.await(5, TimeUnit.SECONDS));
            Future<?> transfer = pool.submit(() -> transfers.transfer("closed-race-" + UUID.randomUUID(),
                new TransferRequest(from, to, 1, "USD"), null));
            try {
                org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(5)).until(() -> jdbc.sql(
                    "SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE ? = ANY(pg_blocking_pids(pid)))")
                    .param(writerPid.get()).query(Boolean.class).single());
            } finally { release.countDown(); }
            closure.get(10, TimeUnit.SECONDS);
            ExecutionException error = assertThrows(ExecutionException.class, () -> transfer.get(10, TimeUnit.SECONDS));
            assertInstanceOf(com.ledgerline.ledger.exception.InvalidTransferException.class, error.getCause());
        }
        assertEquals(1, accounts.getAccount(from).balanceMinor());
        assertEquals(0, accounts.getAccount(to).balanceMinor());
    }

    @Test void databaseRejectsMixedCurrencyAndZeroPostings() {
        UUID usd = account(AccountType.CUSTOMER, 0);
        UUID eur = accounts.createAccount(new CreateAccountRequest("EUR", AccountType.CUSTOMER)).id();
        UUID txId = UUID.randomUUID();
        assertThrows(Exception.class, () -> new org.springframework.transaction.support.TransactionTemplate(manager).execute(status -> {
            jdbc.sql("INSERT INTO transactions (id,idempotency_key,request_hash,status) VALUES (?,?,?,'POSTED')")
                .params(txId, "currency-" + txId, new byte[32]).update();
            jdbc.sql("INSERT INTO postings (transaction_id,account_id,amount_minor,currency) VALUES (?,?,-1,'USD')").params(txId, usd).update();
            jdbc.sql("INSERT INTO postings (transaction_id,account_id,amount_minor,currency) VALUES (?,?,1,'EUR')").params(txId, eur).update();
            return null;
        }));
        assertEquals(0, jdbc.sql("SELECT count(*) FROM transactions WHERE id = ?").param(txId).query(Integer.class).single());
        assertThrows(Exception.class, () -> new org.springframework.transaction.support.TransactionTemplate(manager).execute(status -> {
            jdbc.sql("INSERT INTO transactions (id,idempotency_key,request_hash,status) VALUES (?,?,?,'POSTED')")
                .params(txId, "zero-" + txId, new byte[32]).update();
            jdbc.sql("INSERT INTO postings (transaction_id,account_id,amount_minor,currency) VALUES (?,?,0,'USD')").params(txId, usd).update();
            return null;
        }));
        assertThrows(Exception.class, () -> jdbc.sql("UPDATE accounts SET status = 'UNKNOWN' WHERE id = ?").param(usd).update());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = IsolationVariant.class, names = {"VARIANT_1_PESSIMISTIC", "VARIANT_2_OPTIMISTIC", "VARIANT_3_SERIALIZABLE"})
    void concurrentDuplicateThatExhaustsFundsStillReturnsOriginalResult(IsolationVariant variant) throws Exception {
        UUID from = account(AccountType.CUSTOMER, 1), to = account(AccountType.CUSTOMER, 0);
        String key = "exhaust-" + UUID.randomUUID();
        TransferRequest request = new TransferRequest(from, to, 1, "USD");
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            CyclicBarrier start = new CyclicBarrier(2);
            Callable<TransferResponse> call = () -> { start.await(5, TimeUnit.SECONDS); return transfers.transfer(key, request, variant); };
            Future<TransferResponse> first = pool.submit(call), second = pool.submit(call);
            assertEquals(first.get(10, TimeUnit.SECONDS).transactionId(), second.get(10, TimeUnit.SECONDS).transactionId());
        }
        assertEquals(0, accounts.getAccount(from).balanceMinor());
        assertEquals(1, accounts.getAccount(to).balanceMinor());
    }

    @Test void concurrentDebitsCannotOverdraw() throws Exception {
        UUID from = account(AccountType.CUSTOMER, 1), to = account(AccountType.CUSTOMER, 0);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            CyclicBarrier start = new CyclicBarrier(2);
            Callable<Boolean> call = () -> {
                start.await(5, TimeUnit.SECONDS);
                try { transfers.transfer("debit-" + UUID.randomUUID(), new TransferRequest(from, to, 1, "USD"), null); return true; }
                catch (InsufficientFundsException expected) { return false; }
            };
            Future<Boolean> first = pool.submit(call), second = pool.submit(call);
            assertNotEquals(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
        }
        assertEquals(0, accounts.getAccount(from).balanceMinor());
        assertEquals(1, accounts.getAccount(to).balanceMinor());
    }
}
