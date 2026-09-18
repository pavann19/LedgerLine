package com.ledgerline.ledger.isolation;

import com.ledgerline.ledger.concurrency.BaseIntegrationTest;
import com.ledgerline.ledger.domain.AccountType;
import com.ledgerline.ledger.domain.IsolationVariant;
import com.ledgerline.ledger.dto.AccountResponse;
import com.ledgerline.ledger.dto.CreateAccountRequest;
import com.ledgerline.ledger.dto.TransferRequest;
import com.ledgerline.ledger.repository.PostingRepository;
import com.ledgerline.ledger.service.AccountService;
import com.ledgerline.ledger.service.TransferService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class IsolationExperimentTest extends BaseIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(IsolationExperimentTest.class);

    @Autowired
    private TransferService transferService;

    @Autowired
    private AccountService accountService;

    @Autowired
    private PostingRepository postingRepository;

    @Autowired
    private JdbcClient jdbcClient;

    /**
     * Variant 0 is a race condition, not a deterministic failure: a single trial can get lucky
     * and interleave cleanly. So this runs the contended workload TRIALS times against fresh
     * accounts and requires the drift to reproduce in at least MIN_OBSERVED_ANOMALIES of them —
     * an assertion, not just a log line — and reports the observed anomaly rate.
     */
    @Test
    @DisplayName("Variant 0 (Broken): Demonstrates lost-update anomaly under concurrent contention")
    void demonstrateLostUpdateAnomalyInVariant0() throws Exception {
        int trials = 10;
        int anomalies = 0;
        long totalDrift = 0;

        for (int t = 0; t < trials; t++) {
            long drift = runVariant0Trial();
            if (drift != 0) {
                anomalies++;
                totalDrift += Math.abs(drift);
            }
        }

        double observedRate = (double) anomalies / trials;
        log.info("Variant 0 lost-update anomaly observed in {}/{} trials (rate={}), total absolute drift={}",
            anomalies, trials, observedRate, totalDrift);

        int minObservedAnomalies = 1;
        assertTrue(anomalies >= minObservedAnomalies,
            "Expected the lost-update anomaly to reproduce in at least " + minObservedAnomalies
                + "/" + trials + " trials under contention, but observed " + anomalies
                + "/" + trials + " (rate=" + observedRate + "). "
                + "Variant 0 is a deliberately unlocked read-then-write and should drift reliably; "
                + "if this now passes clean, the broken variant may have accidentally been fixed.");
    }

    /**
     * Runs one contended-transfer trial against a fresh pair of accounts and returns the drift
     * between the postings-derived balance and the cached account_balances row (0 = no anomaly).
     */
    private long runVariant0Trial() throws Exception {
        AccountResponse from = accountService.createAccount(new CreateAccountRequest("USD", AccountType.CUSTOMER));
        AccountResponse to = accountService.createAccount(new CreateAccountRequest("USD", AccountType.CUSTOMER));
        long initialFunds = 100_000L;
        fundAccount(from.id(), initialFunds);
        fundAccount(to.id(), initialFunds);

        int concurrency = 16;
        int transfersPerThread = 10;
        long transferAmount = 100L;
        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(concurrency);
        AtomicInteger successCount = new AtomicInteger(0);

        for (int i = 0; i < concurrency; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int j = 0; j < transfersPerThread; j++) {
                        String key = "broken-" + UUID.randomUUID();
                        TransferRequest req = new TransferRequest(from.id(), to.id(), transferAmount, "USD");
                        transferService.transfer(key, req, IsolationVariant.VARIANT_0_BROKEN);
                        successCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    log.error("Error in variant 0 thread: ", e);
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        doneLatch.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        int executed = successCount.get();
        assertTrue(executed > 10, "Should have executed multiple concurrent transfers");

        long postingsSumFrom = postingRepository.getSumByAccountId(from.id());
        AccountResponse fromAfter = accountService.getAccount(from.id());
        long expectedFromBalance = initialFunds + postingsSumFrom;

        return expectedFromBalance - fromAfter.balanceMinor();
    }

    @Test
    @DisplayName("Variant 1 (Pessimistic): Preserves perfect balance integrity under heavy contention")
    void verifyPessimisticMaintainsIntegrityUnderContention() throws Exception {
        runContentionTest(IsolationVariant.VARIANT_1_PESSIMISTIC);
    }

    @Test
    @DisplayName("Variant 2 (Optimistic): Preserves perfect balance integrity under contention via retries")
    void verifyOptimisticMaintainsIntegrityUnderContention() throws Exception {
        runContentionTest(IsolationVariant.VARIANT_2_OPTIMISTIC);
    }

    @Test
    @DisplayName("Variant 3 (Serializable): Preserves perfect balance integrity with retry on 40001")
    void verifySerializableMaintainsIntegrityUnderContention() throws Exception {
        runContentionTest(IsolationVariant.VARIANT_3_SERIALIZABLE);
    }

    private void runContentionTest(IsolationVariant variant) throws Exception {
        AccountResponse from = accountService.createAccount(new CreateAccountRequest("USD", AccountType.CUSTOMER));
        AccountResponse to = accountService.createAccount(new CreateAccountRequest("USD", AccountType.CUSTOMER));
        long initialFunds = 100_000L;
        fundAccount(from.id(), initialFunds);
        fundAccount(to.id(), initialFunds);

        int concurrency = 10;
        int transfersPerThread = 10;
        long transferAmount = 50L;
        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(concurrency);
        AtomicInteger successCount = new AtomicInteger(0);

        for (int i = 0; i < concurrency; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int j = 0; j < transfersPerThread; j++) {
                        String key = variant.name() + "-" + UUID.randomUUID();
                        TransferRequest req = new TransferRequest(from.id(), to.id(), transferAmount, "USD");
                        transferService.transfer(key, req, variant);
                        successCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    log.error("Error in {} thread: {}", variant, e.getMessage());
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        doneLatch.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        assertTrue(successCount.get() > 0, "Transfers should succeed");

        long postingsSumFrom = postingRepository.getSumByAccountId(from.id());
        AccountResponse fromAfter = accountService.getAccount(from.id());
        long expectedFromBalance = initialFunds + postingsSumFrom;

        assertEquals(expectedFromBalance, fromAfter.balanceMinor(),
            variant + " must maintain exact zero-drift between postings sum and cached balance!");
    }

    private void fundAccount(UUID accountId, long amountMinor) {
        jdbcClient.sql("UPDATE account_balances SET balance_minor = balance_minor + ? WHERE account_id = ?")
            .params(amountMinor, accountId)
            .update();
    }
}
