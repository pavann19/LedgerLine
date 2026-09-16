package com.ledgerline.projection.service;

import com.ledgerline.projection.model.DailyAccountSummary;
import com.ledgerline.projection.model.StatementViewEntry;
import com.ledgerline.projection.model.TransferEventPayload;
import com.ledgerline.projection.repository.ProcessedEventsRepository;
import com.ledgerline.projection.repository.ProjectionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class ProjectionService {

    private static final Logger log = LoggerFactory.getLogger(ProjectionService.class);

    private final ProcessedEventsRepository processedEventsRepository;
    private final ProjectionRepository projectionRepository;

    public ProjectionService(
        ProcessedEventsRepository processedEventsRepository,
        ProjectionRepository projectionRepository
    ) {
        this.processedEventsRepository = processedEventsRepository;
        this.projectionRepository = projectionRepository;
    }

    /**
     * Projects a TransferPosted event into statement_view and daily_account_summary.
     * Guaranteed idempotent by checking processed_events table in the same DB transaction.
     */
    @Transactional
    public boolean projectTransfer(TransferEventPayload payload) {
        boolean inserted = processedEventsRepository.tryMarkProcessed(payload.eventId(), Instant.now());
        if (!inserted) {
            log.info("Deduplication: Event {} already processed. Skipping projection.", payload.eventId());
            return false;
        }

        LocalDate summaryDate = payload.timestamp().atZone(ZoneOffset.UTC).toLocalDate();

        // 1. Project Debit (fromAccount: negative flow)
        long fromPrevBalance = projectionRepository.getLatestRunningBalance(payload.fromAccountId());
        long fromNewBalance = fromPrevBalance - payload.amountMinor();
        projectionRepository.insertStatementView(
            payload.fromAccountId(),
            payload.transactionId(),
            -payload.amountMinor(),
            fromNewBalance,
            payload.currency(),
            payload.timestamp()
        );
        projectionRepository.upsertDailySummary(
            payload.fromAccountId(),
            summaryDate,
            -payload.amountMinor(),
            fromNewBalance
        );

        // 2. Project Credit (toAccount: positive flow)
        long toPrevBalance = projectionRepository.getLatestRunningBalance(payload.toAccountId());
        long toNewBalance = toPrevBalance + payload.amountMinor();
        projectionRepository.insertStatementView(
            payload.toAccountId(),
            payload.transactionId(),
            payload.amountMinor(),
            toNewBalance,
            payload.currency(),
            payload.timestamp()
        );
        projectionRepository.upsertDailySummary(
            payload.toAccountId(),
            summaryDate,
            payload.amountMinor(),
            toNewBalance
        );

        log.debug("Successfully projected event {} for transaction {}", payload.eventId(), payload.transactionId());
        return true;
    }

    @Transactional(readOnly = true)
    public List<StatementViewEntry> getStatement(UUID accountId, int limit, int offset) {
        return projectionRepository.getStatement(accountId, limit, offset);
    }

    @Transactional(readOnly = true)
    public Optional<DailyAccountSummary> getDailySummary(UUID accountId, LocalDate date) {
        return projectionRepository.getDailySummary(accountId, date);
    }
}
