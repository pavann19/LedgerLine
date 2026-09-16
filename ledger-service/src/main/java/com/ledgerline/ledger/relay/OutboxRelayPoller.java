package com.ledgerline.ledger.relay;

import com.ledgerline.ledger.domain.OutboxEvent;
import com.ledgerline.ledger.repository.OutboxRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
@ConditionalOnProperty(name = "ledger.outbox.relay.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxRelayPoller {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelayPoller.class);

    private final OutboxRepository outboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final String topic;
    private final int batchSize;

    // Test hook for failure injection testing (Milestone 3)
    private volatile boolean simulateCrashAfterSend = false;

    public OutboxRelayPoller(
        OutboxRepository outboxRepository,
        KafkaTemplate<String, String> kafkaTemplate,
        MeterRegistry meterRegistry,
        @Value("${ledger.kafka.transfers-topic:ledger.transfers.v1}") String topic,
        @Value("${ledger.outbox.relay.batch-size:50}") int batchSize
    ) {
        this.outboxRepository = outboxRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
        this.batchSize = batchSize;

        Gauge.builder("ledger.outbox.backlog.size", outboxRepository, OutboxRepository::getBacklogCount)
            .description("Count of unpublished outbox events")
            .register(meterRegistry);

        Gauge.builder("ledger.outbox.oldest_unpublished_age_seconds", outboxRepository, OutboxRepository::getOldestUnpublishedAgeSeconds)
            .description("Age in seconds of oldest unpublished outbox event")
            .register(meterRegistry);
    }

    public void setSimulateCrashAfterSend(boolean simulate) {
        this.simulateCrashAfterSend = simulate;
    }

    @Scheduled(fixedDelayString = "${ledger.outbox.relay.delay-ms:500}")
    @Transactional
    public int pollAndPublish() {
        List<OutboxEvent> unpublishedEvents = outboxRepository.fetchUnpublishedForUpdateSkipLocked(batchSize);
        if (unpublishedEvents.isEmpty()) {
            return 0;
        }

        int publishedCount = 0;
        for (OutboxEvent event : unpublishedEvents) {
            try {
                // Publish to Kafka with aggregateId key to preserve partition ordering
                kafkaTemplate.send(topic, event.aggregateId().toString(), event.payload())
                    .get(5, TimeUnit.SECONDS);

                // Check failure test hook
                if (simulateCrashAfterSend) {
                    log.warn("SIMULATED CRASH TEST HOOK ACTIVATED: Crashing relay after Kafka send before marking published!");
                    throw new RuntimeException("Simulated relay worker crash mid-publish");
                }

                outboxRepository.markPublished(event.id(), Instant.now());
                publishedCount++;
            } catch (Exception e) {
                log.error("Failed to publish outbox event {}: {}", event.id(), e.getMessage());
                // Break to allow next polling iteration to retry
                break;
            }
        }
        return publishedCount;
    }
}
