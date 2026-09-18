package com.ledgerline.ledger.failure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerline.ledger.concurrency.BaseIntegrationTest;
import com.ledgerline.ledger.domain.AccountType;
import com.ledgerline.ledger.domain.OutboxEvent;
import com.ledgerline.ledger.dto.AccountResponse;
import com.ledgerline.ledger.dto.CreateAccountRequest;
import com.ledgerline.ledger.dto.TransferEventPayload;
import com.ledgerline.ledger.dto.TransferRequest;
import com.ledgerline.ledger.relay.OutboxRelayPoller;
import com.ledgerline.ledger.repository.OutboxRepository;
import com.ledgerline.ledger.service.AccountService;
import com.ledgerline.ledger.service.TransferService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.DockerClientFactory;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises the async (outbox -> Kafka) failure paths against a REAL Kafka broker
 * (Testcontainers), not a mock. The broker is paused (frozen) and unpaused mid-test to
 * reproduce the "Kafka down" scenario the docs describe.
 */
@TestPropertySource(properties = "ledger.outbox.relay.enabled=true")
class AsyncFailureAndResilienceTest extends BaseIntegrationTest {

    private static final KafkaContainer kafka =
        new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));

    @BeforeAll
    static void startKafka() {
        kafka.start();
    }

    @AfterAll
    static void stopKafka() {
        kafka.stop();
    }

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
    }

    @Autowired
    private TransferService transferService;

    @Autowired
    private AccountService accountService;

    @Autowired
    private OutboxRepository outboxRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private OutboxRelayPoller outboxRelayPoller;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private org.springframework.jdbc.core.simple.JdbcClient jdbcClient;

    @Test
    @DisplayName("Kafka Down: Synchronous transfers succeed, outbox backlog accumulates, and drains after the broker restarts")
    void shouldAccumulateOutboxBacklogWhenKafkaIsDownAndDrainAfterRecovery() {
        AccountResponse from = accountService.createAccount(new CreateAccountRequest("USD", AccountType.CUSTOMER));
        AccountResponse to = accountService.createAccount(new CreateAccountRequest("USD", AccountType.CUSTOMER));
        fundAccount(from.id(), 50000L);

        long backlogBefore = outboxRepository.getBacklogCount();

        // Transfer commits synchronously to DB even though nothing has published it to Kafka yet.
        String key = "kafka-down-" + UUID.randomUUID();
        assertDoesNotThrow(() ->
            transferService.transfer(key, new TransferRequest(from.id(), to.id(), 1500L, "USD"), null));

        long backlogAfterTransfer = outboxRepository.getBacklogCount();
        assertEquals(backlogBefore + 1, backlogAfterTransfer, "Outbox backlog must record the new event before any publish attempt");

        // Freeze the real broker process (SIGSTOP via docker pause) so it becomes unreachable without
        // losing its container/port, then try to relay: the send must fail cleanly, leaving the row unpublished.
        // (A hard stop+restart would reassign the mapped port and strand the already-initialized
        // KafkaTemplate's producer client, so pause/unpause is used to simulate the outage instead.)
        DockerClientFactory.instance().client().pauseContainerCmd(kafka.getContainerId()).exec();
        try {
            int published = outboxRelayPoller.pollAndPublish();
            assertEquals(0, published, "No events should be marked published while the broker is unreachable");
        } finally {
            DockerClientFactory.instance().client().unpauseContainerCmd(kafka.getContainerId()).exec();
        }

        long backlogWhileDown = outboxRepository.getBacklogCount();
        assertEquals(backlogAfterTransfer, backlogWhileDown, "Backlog must not shrink while Kafka is unreachable");

        // Once the broker is back, the poller must drain the backlog. Give it a generous window and a
        // steady poll interval: right after `docker unpause`, the broker still needs time to finish its
        // own startup/leader-election before it will accept produce requests again, and each failed
        // attempt inside pollAndPublish() can itself take up to 5s (the send's own timeout).
        await()
            .atMost(Duration.ofSeconds(90))
            .pollInterval(Duration.ofSeconds(2))
            .untilAsserted(() -> {
                outboxRelayPoller.pollAndPublish();
                assertEquals(0, outboxRepository.getBacklogCount(), "Backlog must fully drain after broker recovery");
            });
    }

    @Test
    @DisplayName("Worker Crash Mid-Publish: event stays unpublished after the crash and is re-published on the next poll")
    void shouldRepublishAfterSimulatedCrashBetweenSendAndMarkPublished() {
        AccountResponse from = accountService.createAccount(new CreateAccountRequest("USD", AccountType.CUSTOMER));
        AccountResponse to = accountService.createAccount(new CreateAccountRequest("USD", AccountType.CUSTOMER));
        fundAccount(from.id(), 50000L);

        String key = "worker-crash-" + UUID.randomUUID();
        transferService.transfer(key, new TransferRequest(from.id(), to.id(), 750L, "USD"), null);

        long backlogBeforeCrash = outboxRepository.getBacklogCount();
        assertTrue(backlogBeforeCrash > 0, "Expected an unpublished outbox row for the transfer");

        // Simulate the relay crashing after the Kafka send succeeds but before markPublished() runs.
        // OutboxRelayPoller.pollAndPublish() catches per-event exceptions internally (so one bad event
        // doesn't kill the whole poll cycle or the @Scheduled thread) and just stops the batch early,
        // returning the count published *before* the crash rather than throwing out to the caller.
        outboxRelayPoller.setSimulateCrashAfterSend(true);
        int publishedDuringCrash = outboxRelayPoller.pollAndPublish();
        assertEquals(0, publishedDuringCrash, "No events should be marked published in the poll cycle that crashed");

        long backlogAfterCrash = outboxRepository.getBacklogCount();
        assertEquals(backlogBeforeCrash, backlogAfterCrash,
            "The event must remain unpublished after the simulated crash (Kafka send happened, but markPublished did not)");

        // Recovery: the next poll (crash disabled) must successfully re-send and mark it published.
        outboxRelayPoller.setSimulateCrashAfterSend(false);
        int published = outboxRelayPoller.pollAndPublish();
        assertTrue(published > 0, "The relay must re-publish the event on the next poll after recovering from the crash");

        long backlogAfterRecovery = outboxRepository.getBacklogCount();
        assertEquals(backlogAfterCrash - published, backlogAfterRecovery,
            "Backlog must decrease by exactly the number of events republished");
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
