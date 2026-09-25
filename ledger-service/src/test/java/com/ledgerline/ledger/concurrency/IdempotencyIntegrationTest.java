package com.ledgerline.ledger.concurrency;

import com.ledgerline.ledger.domain.AccountType;
import com.ledgerline.ledger.dto.AccountResponse;
import com.ledgerline.ledger.dto.CreateAccountRequest;
import com.ledgerline.ledger.dto.TransferRequest;
import com.ledgerline.ledger.dto.TransferResponse;
import com.ledgerline.ledger.exception.IdempotencyConflictException;
import com.ledgerline.ledger.service.AccountService;
import com.ledgerline.ledger.service.TransferService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

class IdempotencyIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private TransferService transferService;

    @Autowired
    private AccountService accountService;

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    @DisplayName("Idempotency: Replaying same key with identical payload returns stored result")
    void shouldReturnStoredResultOnIdenticalReplay() {
        AccountResponse from = accountService.createAccount(new CreateAccountRequest("USD", AccountType.CUSTOMER));
        AccountResponse to = accountService.createAccount(new CreateAccountRequest("USD", AccountType.CUSTOMER));
        fundAccount(from.id(), 10000L);

        String idempotencyKey = "idemp-" + UUID.randomUUID();
        TransferRequest request = new TransferRequest(from.id(), to.id(), 2500L, "USD");

        TransferResponse initial = transferService.transfer(idempotencyKey, request, null);
        assertFalse(initial.idempotentReplay());

        TransferResponse replay = transferService.transfer(idempotencyKey, request, null);
        assertTrue(replay.idempotentReplay());
        assertEquals(initial.transactionId(), replay.transactionId());
        assertEquals(initial.idempotencyKey(), replay.idempotencyKey());

        // Verify balance changed only once (10000 - 2500 = 7500)
        AccountResponse fromAfter = accountService.getAccount(from.id());
        assertEquals(7500L, fromAfter.balanceMinor());
    }

    @Test
    @DisplayName("Idempotency: Replaying same key with different payload throws 422 conflict")
    void shouldThrowConflictWhenReusingKeyWithDifferentPayload() {
        AccountResponse from = accountService.createAccount(new CreateAccountRequest("USD", AccountType.CUSTOMER));
        AccountResponse to = accountService.createAccount(new CreateAccountRequest("USD", AccountType.CUSTOMER));
        fundAccount(from.id(), 10000L);

        String idempotencyKey = "idemp-" + UUID.randomUUID();
        TransferRequest req1 = new TransferRequest(from.id(), to.id(), 2000L, "USD");
        TransferRequest req2 = new TransferRequest(from.id(), to.id(), 3000L, "USD"); // different amount

        transferService.transfer(idempotencyKey, req1, null);

        assertThrows(IdempotencyConflictException.class, () -> {
            transferService.transfer(idempotencyKey, req2, null);
        });
    }

    @Test
    @DisplayName("Idempotency Storm: 20 concurrent threads with same key produce exactly 1 transaction")
    void shouldHandleConcurrentDuplicateRequestsCleanly() throws Exception {
        AccountResponse from = accountService.createAccount(new CreateAccountRequest("USD", AccountType.CUSTOMER));
        AccountResponse to = accountService.createAccount(new CreateAccountRequest("USD", AccountType.CUSTOMER));
        fundAccount(from.id(), 50000L);

        String idempotencyKey = "storm-" + UUID.randomUUID();
        TransferRequest request = new TransferRequest(from.id(), to.id(), 1000L, "USD");

        int concurrency = 20;
        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        CyclicBarrier barrier = new CyclicBarrier(concurrency);
        List<Future<TransferResponse>> futures = new ArrayList<>();

        for (int i = 0; i < concurrency; i++) {
            futures.add(executor.submit(() -> {
                barrier.await();
                return transferService.transfer(idempotencyKey, request, null);
            }));
        }

        UUID commonTxId = null;
        for (Future<TransferResponse> future : futures) {
            TransferResponse res = future.get(10, TimeUnit.SECONDS);
            assertNotNull(res);
            if (commonTxId == null) {
                commonTxId = res.transactionId();
            } else {
                assertEquals(commonTxId, res.transactionId(), "All responses must return the same transaction ID");
            }
        }
        executor.shutdown();

        // Check database: exactly 1 transaction row
        Integer txCount = jdbcClient.sql("SELECT COUNT(1) FROM transactions WHERE idempotency_key = ?")
            .param(idempotencyKey)
            .query(Integer.class)
            .single();
        assertEquals(1, txCount);

        // Check postings: exactly 2 postings (debit and credit)
        Integer postingCount = jdbcClient.sql("SELECT COUNT(1) FROM postings WHERE transaction_id = ?")
            .param(commonTxId)
            .query(Integer.class)
            .single();
        assertEquals(2, postingCount);

        // Check balance: exactly one transfer was applied
        AccountResponse fromAfter = accountService.getAccount(from.id());
        assertEquals(49000L, fromAfter.balanceMinor());
    }

    @Test
    @DisplayName("Idempotency: The same external key is independent across principals")
    void shouldScopeTheSameExternalKeyByPrincipal() {
        AccountResponse from = accountService.createAccount(new CreateAccountRequest("USD", AccountType.CUSTOMER), "customer-a");
        AccountResponse to = accountService.createAccount(new CreateAccountRequest("USD", AccountType.CUSTOMER), "customer-a");
        fundAccount(from.id(), 10000L);

        String key = "shared-" + UUID.randomUUID();
        TransferRequest request = new TransferRequest(from.id(), to.id(), 1000L, "USD");
        TransferResponse first = transferService.transfer(key, request, null, "customer-a", false);
        TransferResponse second = transferService.transfer(key, request, null, "operator-b", true);

        assertNotEquals(first.transactionId(), second.transactionId());
        Integer rows = jdbcClient.sql("SELECT COUNT(*) FROM transactions WHERE idempotency_key = ? AND principal_id IN ('customer-a', 'operator-b')")
            .param(key).query(Integer.class).single();
        assertEquals(2, rows);
    }

    private void fundAccount(UUID accountId, long amountMinor) {
        jdbcClient.sql("UPDATE account_balances SET balance_minor = balance_minor + ? WHERE account_id = ?")
            .params(amountMinor, accountId)
            .update();
    }
}
