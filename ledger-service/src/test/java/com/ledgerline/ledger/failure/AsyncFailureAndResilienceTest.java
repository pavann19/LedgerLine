package com.ledgerline.ledger.failure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerline.ledger.concurrency.BaseIntegrationTest;
import com.ledgerline.ledger.domain.AccountType;
import com.ledgerline.ledger.domain.OutboxEvent;
import com.ledgerline.ledger.dto.AccountResponse;
import com.ledgerline.ledger.dto.CreateAccountRequest;
import com.ledgerline.ledger.dto.TransferEventPayload;
import com.ledgerline.ledger.dto.TransferRequest;
import com.ledgerline.ledger.repository.OutboxRepository;
import com.ledgerline.ledger.service.AccountService;
import com.ledgerline.ledger.service.TransferService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class AsyncFailureAndResilienceTest extends BaseIntegrationTest {

    @Autowired
    private TransferService transferService;

    @Autowired
    private AccountService accountService;

    @Autowired
    private OutboxRepository outboxRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private org.springframework.jdbc.core.simple.JdbcClient jdbcClient;

    @MockBean
    private KafkaTemplate<String, String> kafkaTemplate;

    @Test
    @DisplayName("Kafka Down: Synchronous transfers succeed and outbox backlog accumulates")
    void shouldAccumulateOutboxBacklogWhenKafkaIsDown() {
        AccountResponse from = accountService.createAccount(new CreateAccountRequest("USD", AccountType.CUSTOMER));
        AccountResponse to = accountService.createAccount(new CreateAccountRequest("USD", AccountType.CUSTOMER));
        fundAccount(from.id(), 50000L);

        // When Kafka is down, sending to Kafka fails
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
            .thenReturn(CompletableFuture.failedFuture(new RuntimeException("Kafka Broker Unavailable (Simulated)")));

        long backlogBefore = outboxRepository.getBacklogCount();

        // Transfer commits synchronously to DB without requiring synchronous Kafka
        String key = "kafka-down-" + UUID.randomUUID();
        assertDoesNotThrow(() -> {
            transferService.transfer(key, new TransferRequest(from.id(), to.id(), 1500L, "USD"), null);
        });

        long backlogAfter = outboxRepository.getBacklogCount();
        assertEquals(backlogBefore + 1, backlogAfter, "Outbox backlog must increment while Kafka is down");
    }

    @Test
    @DisplayName("Outbox Poller SKIP LOCKED: Competing workers do not duplicate batches")
    void shouldPreventDuplicateRelayUnderCompetingWorkers() {
        AccountResponse from = accountService.createAccount(new CreateAccountRequest("USD", AccountType.CUSTOMER));
        AccountResponse to = accountService.createAccount(new CreateAccountRequest("USD", AccountType.CUSTOMER));
        fundAccount(from.id(), 50000L);

        transferService.transfer("relay-worker-1-" + UUID.randomUUID(), new TransferRequest(from.id(), to.id(), 100L, "USD"), null);
        transferService.transfer("relay-worker-2-" + UUID.randomUUID(), new TransferRequest(from.id(), to.id(), 200L, "USD"), null);

        TransactionTemplate tx1 = new TransactionTemplate(transactionManager);
        TransactionTemplate tx2 = new TransactionTemplate(transactionManager);
        ExecutorService worker2Executor = Executors.newSingleThreadExecutor();

        try {
            // Worker 1 starts transaction and locks rows with SKIP LOCKED
            tx1.execute(status1 -> {
                List<OutboxEvent> batch1 = outboxRepository.fetchUnpublishedForUpdateSkipLocked(10);
                assertFalse(batch1.isEmpty(), "Worker 1 should pick up rows");

                // Concurrent Worker 2 on separate thread attempts to fetch: SKIP LOCKED must skip rows locked by Worker 1
                try {
                    Future<List<OutboxEvent>> future = worker2Executor.submit(() ->
                        tx2.execute(status2 -> outboxRepository.fetchUnpublishedForUpdateSkipLocked(10))
                    );
                    List<OutboxEvent> batch2 = future.get(5, TimeUnit.SECONDS);

                    // Worker 2 must not receive any rows that Worker 1 has locked
                    for (OutboxEvent e1 : batch1) {
                        for (OutboxEvent e2 : batch2) {
                            assertNotEquals(e1.id(), e2.id(), "SKIP LOCKED failed: same outbox row acquired by both workers!");
                        }
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                return null;
            });
        } finally {
            worker2Executor.shutdown();
        }
    }

    private void fundAccount(UUID accountId, long amountMinor) {
        jdbcClient.sql("UPDATE account_balances SET balance_minor = balance_minor + ? WHERE account_id = ?")
            .params(amountMinor, accountId)
            .update();
    }
}
