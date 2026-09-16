package com.ledgerline.projection.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerline.projection.model.TransferEventPayload;
import com.ledgerline.projection.service.ProjectionService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Component
public class TransferEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(TransferEventConsumer.class);

    private final ProjectionService projectionService;
    private final ObjectMapper objectMapper;

    // Test hook for M3 chaos testing: crash after DB commit, before Kafka offset commit
    private volatile boolean simulateCrashBeforeOffsetCommit = false;

    public TransferEventConsumer(ProjectionService projectionService, ObjectMapper objectMapper) {
        this.projectionService = projectionService;
        this.objectMapper = objectMapper;
    }

    public void setSimulateCrashBeforeOffsetCommit(boolean simulate) {
        this.simulateCrashBeforeOffsetCommit = simulate;
    }

    @KafkaListener(
        topics = "${projection.kafka.transfers-topic:ledger.transfers.v1}",
        groupId = "${spring.kafka.consumer.group-id:ledgerline-projection}"
    )
    public void onMessage(ConsumerRecord<String, String> record, Acknowledgment ack) {
        try {
            TransferEventPayload payload = objectMapper.readValue(record.value(), TransferEventPayload.class);
            log.info("Received event for projection: eventId={}, tx={}", payload.eventId(), payload.transactionId());

            // 1. Process and commit in DB transaction
            projectionService.projectTransfer(payload);

            // 2. Failure test hook
            if (simulateCrashBeforeOffsetCommit) {
                log.warn("SIMULATED CRASH TEST HOOK: Crashing after DB commit before Kafka offset commit!");
                throw new RuntimeException("Simulated consumer crash after DB commit before offset commit");
            }

            // 3. Commit Kafka offset ONLY after DB transaction successfully committed
            if (ack != null) {
                ack.acknowledge();
            }
        } catch (Exception e) {
            log.error("Error processing projection event from Kafka: {}", e.getMessage(), e);
            throw new RuntimeException("Failed processing projection event", e);
        }
    }
}
