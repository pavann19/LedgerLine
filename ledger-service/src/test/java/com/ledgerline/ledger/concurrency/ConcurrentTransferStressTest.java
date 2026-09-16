package com.ledgerline.ledger.concurrency;

import com.ledgerline.ledger.domain.AccountType;
import com.ledgerline.ledger.dto.AccountResponse;
import com.ledgerline.ledger.dto.CreateAccountRequest;
import com.ledgerline.ledger.dto.TransferRequest;
import com.ledgerline.ledger.exception.InsufficientFundsException;
import com.ledgerline.ledger.repository.PostingRepository;
import com.ledgerline.ledger.service.AccountService;
import com.ledgerline.ledger.service.TransferService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ConcurrentTransferStressTest extends BaseIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(ConcurrentTransferStressTest.class);

    @Autowired
    private TransferService transferService;

    @Autowired
    private AccountService accountService;

    @Autowired
    private PostingRepository postingRepository;

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    @DisplayName("MVP Exit Criterion: 50 threads x transfers across 20 accounts preserve global invariants")
    void shouldPassConcurrencyStressTestWithAllInvariantsPreserved() throws Exception {
        int numAccounts = 20;
        long initialBalancePerAccount = 100_000L; // 1,000.00 USD each
        long totalInitialSupply = numAccounts * initialBalancePerAccount;

        List<UUID> accountIds = new ArrayList<>();
        for (int i = 0; i < numAccounts; i++) {
            AccountResponse acc = accountService.createAccount(new CreateAccountRequest("USD", AccountType.CUSTOMER));
            fundAccount(acc.id(), initialBalancePerAccount);
            accountIds.add(acc.id());
        }

        int threadCount = 50;
        int transfersPerThread = 20; // 1,000 total transfer attempts
        int totalRequests = threadCount * transfersPerThread;

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(totalRequests);

        AtomicInteger successfulTransfers = new AtomicInteger(0);
        AtomicInteger insufficientFundsCount = new AtomicInteger(0);
        AtomicInteger unexpectedErrors = new AtomicInteger(0);

        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    startLatch.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }

                for (int i = 0; i < transfersPerThread; i++) {
                    try {
                        int fromIdx = ThreadLocalRandom.current().nextInt(numAccounts);
                        int toIdx;
                        do {
                            toIdx = ThreadLocalRandom.current().nextInt(numAccounts);
                        } while (fromIdx == toIdx);

                        UUID from = accountIds.get(fromIdx);
                        UUID to = accountIds.get(toIdx);
                        long amount = ThreadLocalRandom.current().nextLong(10, 500);

                        String idempotencyKey = String.format("stress-t%d-req%d-%s", threadId, i, UUID.randomUUID());
                        TransferRequest req = new TransferRequest(from, to, amount, "USD");

                        transferService.transfer(idempotencyKey, req, null);
                        successfulTransfers.incrementAndGet();
                    } catch (InsufficientFundsException ife) {
                        insufficientFundsCount.incrementAndGet();
                    } catch (Exception e) {
                        log.error("Unexpected error during transfer: ", e);
                        unexpectedErrors.incrementAndGet();
                    } finally {
                        doneLatch.countDown();
                    }
                }
            });
        }

        // Fire all threads simultaneously
        startLatch.countDown();
        boolean completed = doneLatch.await(60, TimeUnit.SECONDS);
        executor.shutdown();

        assertTrue(completed, "Concurrency stress test did not finish within timeout");
        assertEquals(0, unexpectedErrors.get(), "No unexpected errors (e.g. deadlocks) allowed");
        log.info("Stress test finished: {} successful transfers, {} insufficient funds rejections",
            successfulTransfers.get(), insufficientFundsCount.get());

        // ==========================================
        // ASSERTION 1: Global Sum Invariant
        // Sum of all account_balances must equal initial supply
        // ==========================================
        Long totalCurrentBalance = jdbcClient.sql("SELECT SUM(balance_minor) FROM account_balances WHERE account_id IN (:ids)")
            .param("ids", accountIds)
            .query(Long.class)
            .single();
        assertEquals(totalInitialSupply, totalCurrentBalance, "Conservation of money: global sum must remain invariant");

        // ==========================================
        // ASSERTION 2: Non-negative Balance Invariant
        // No customer account balance may be negative
        // ==========================================
        for (UUID accountId : accountIds) {
            AccountResponse acc = accountService.getAccount(accountId);
            assertTrue(acc.balanceMinor() >= 0, "Account " + accountId + " has negative balance: " + acc.balanceMinor());
        }

        // ==========================================
        // ASSERTION 3: Postings Sum equals Balance Invariant
        // For every account: initial_funding + sum(postings) == cached balance
        // ==========================================
        for (UUID accountId : accountIds) {
            long postingsSum = postingRepository.getSumByAccountId(accountId);
            AccountResponse acc = accountService.getAccount(accountId);
            long expectedBalance = initialBalancePerAccount + postingsSum;
            assertEquals(expectedBalance, acc.balanceMinor(),
                "Account " + accountId + " balance drift! Cached=" + acc.balanceMinor() + ", PostingsSum=" + postingsSum);
        }

        // ==========================================
        // ASSERTION 4: Double-Entry Zero-Sum Per Transaction
        // For all transactions: sum(amount_minor) == 0
        // ==========================================
        Long unbalancedTransactions = jdbcClient.sql("""
            SELECT COUNT(1) FROM (
                SELECT transaction_id, SUM(amount_minor) as total
                FROM postings
                GROUP BY transaction_id
                HAVING SUM(amount_minor) <> 0
            ) t
            """).query(Long.class).single();
        assertEquals(0L, unbalancedTransactions, "Every transaction must have zero sum across its postings");
    }

    private void fundAccount(UUID accountId, long amountMinor) {
        jdbcClient.sql("UPDATE account_balances SET balance_minor = balance_minor + ? WHERE account_id = ?")
            .params(amountMinor, accountId)
            .update();
    }
}
