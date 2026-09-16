package com.ledgerline.projection;

import com.ledgerline.projection.model.DailyAccountSummary;
import com.ledgerline.projection.model.StatementViewEntry;
import com.ledgerline.projection.model.TransferEventPayload;
import com.ledgerline.projection.repository.ProcessedEventsRepository;
import com.ledgerline.projection.service.ProjectionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ProjectionDeduplicationTest extends BaseProjectionIntegrationTest {

    @Autowired
    private ProjectionService projectionService;

    @Autowired
    private ProcessedEventsRepository processedEventsRepository;

    @Test
    @DisplayName("Deduplication & Redelivery: Re-processing identical event ID does not double-apply projection")
    void shouldDeduplicateIdenticalEventsUnderMessageRedelivery() {
        UUID eventId = UUID.randomUUID();
        UUID txId = UUID.randomUUID();
        UUID fromAccount = UUID.randomUUID();
        UUID toAccount = UUID.randomUUID();
        long amount = 5000L;
        Instant now = Instant.now();

        TransferEventPayload payload = new TransferEventPayload(
            eventId,
            txId,
            fromAccount,
            toAccount,
            amount,
            "USD",
            now
        );

        // First delivery: projection succeeds
        boolean firstApplied = projectionService.projectTransfer(payload);
        assertTrue(firstApplied, "First event delivery must be processed and applied");

        assertTrue(processedEventsRepository.isProcessed(eventId), "Event ID must be recorded in processed_events");

        // Second delivery (e.g. simulated consumer crash or network retry):
        boolean secondApplied = projectionService.projectTransfer(payload);
        assertFalse(secondApplied, "Redelivered event must be rejected as duplicate");

        // Verify statement_view has exactly 1 entry for fromAccount, not 2
        List<StatementViewEntry> fromStatements = projectionService.getStatement(fromAccount, 10, 0);
        assertEquals(1, fromStatements.size(), "Statement view must have exactly 1 entry despite redelivery");
        assertEquals(-5000L, fromStatements.getFirst().amountMinor());
        assertEquals(-5000L, fromStatements.getFirst().runningBalanceMinor());

        // Verify statement_view has exactly 1 entry for toAccount, not 2
        List<StatementViewEntry> toStatements = projectionService.getStatement(toAccount, 10, 0);
        assertEquals(1, toStatements.size(), "Statement view must have exactly 1 entry despite redelivery");
        assertEquals(5000L, toStatements.getFirst().amountMinor());
        assertEquals(5000L, toStatements.getFirst().runningBalanceMinor());

        // Verify daily summary has posting_count = 1
        LocalDate summaryDate = now.atZone(ZoneOffset.UTC).toLocalDate();
        Optional<DailyAccountSummary> fromSummary = projectionService.getDailySummary(fromAccount, summaryDate);
        assertTrue(fromSummary.isPresent());
        assertEquals(1, fromSummary.get().postingCount(), "Posting count must be 1, not incremented twice");
        assertEquals(5000L, fromSummary.get().totalOutflowMinor());
    }
}
