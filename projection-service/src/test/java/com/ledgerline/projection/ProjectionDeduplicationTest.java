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
    @Autowired private com.ledgerline.projection.consumer.TransferEventConsumer consumer;
    @Autowired private tools.jackson.databind.ObjectMapper mapper;

    @Test void malformedSuffixCannotAcknowledgeOrProjectAHealthyPrefix() throws Exception {
        var payload = new TransferEventPayload(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),7,"USD",Instant.now());
        var good = new org.apache.kafka.clients.consumer.ConsumerRecord<String,String>("test",0,1,"key",mapper.writeValueAsString(payload));
        var bad = new org.apache.kafka.clients.consumer.ConsumerRecord<String,String>("test",0,2,"key","{malformed");
        var acknowledged = new java.util.concurrent.atomic.AtomicBoolean();
        assertThrows(RuntimeException.class, () -> consumer.onMessages(List.of(good,bad), () -> acknowledged.set(true)));
        assertFalse(acknowledged.get());
        assertFalse(processedEventsRepository.isProcessed(payload.eventId()));
        assertTrue(projectionService.getStatement(payload.fromAccountId(),10,0).isEmpty());
    }

    @Test
    void batchAcknowledgesOnlyAfterCommitAndRedeliveryDoesNotDuplicate() throws Exception {
        var payload=new TransferEventPayload(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),7,"USD",Instant.now());
        var record=new org.apache.kafka.clients.consumer.ConsumerRecord<String,String>("test",0,1,"key",mapper.writeValueAsString(payload));
        var acknowledged=new java.util.concurrent.atomic.AtomicBoolean();
        consumer.setSimulateCrashBeforeOffsetCommit(true);
        try {
            assertThrows(RuntimeException.class,()->consumer.onMessages(List.of(record),()->acknowledged.set(true)));
            assertFalse(acknowledged.get());
            assertTrue(processedEventsRepository.isProcessed(payload.eventId()));
        } finally {
            consumer.setSimulateCrashBeforeOffsetCommit(false);
        }
        consumer.onMessages(List.of(record),()->acknowledged.set(true));
        assertTrue(acknowledged.get());
        assertEquals(1,projectionService.getStatement(payload.fromAccountId(),10,0).size());
    }

    @Test
    void batchDeduplicatesAndMatchesFullHistoryWithLateCrossDayEvents() {
        UUID from=UUID.randomUUID(),to=UUID.randomUUID();
        var events=new java.util.ArrayList<TransferEventPayload>();
        var random=new java.util.Random(194);
        Instant base=Instant.parse("2026-10-20T23:59:59.999999900Z");
        for(int i=0;i<80;i++) {
            boolean reverse=random.nextBoolean();
            events.add(new TransferEventPayload(UUID.randomUUID(),UUID.randomUUID(),reverse?to:from,reverse?from:to,
                1+random.nextInt(100),"USD",base.plusSeconds(random.nextInt(180000))));
        }
        events.addAll(java.util.List.copyOf(events.subList(0,10)));
        assertEquals(80,projectionService.projectBatch(events));
        assertEquals(0L,jdbc.sql("""
            SELECT count(*) FROM (SELECT running_balance_minor,sum(amount_minor) OVER(PARTITION BY account_id
                ORDER BY created_at,id) expected FROM statement_view WHERE account_id IN (?,?)) s
            WHERE running_balance_minor<>expected
            """).params(from,to).query(Long.class).single());
        assertEquals(0L,jdbc.sql("""
            WITH expected AS (
                SELECT account_id,(created_at AT TIME ZONE 'UTC')::date summary_date,
                    (array_agg(running_balance_minor-amount_minor ORDER BY created_at,id))[1] opening,
                    (array_agg(running_balance_minor ORDER BY created_at DESC,id DESC))[1] closing,
                    coalesce(sum(amount_minor) FILTER(WHERE amount_minor>0),0) inflow,
                    coalesce(sum(-amount_minor) FILTER(WHERE amount_minor<0),0) outflow,count(*) postings
                FROM statement_view WHERE account_id IN (?,?) GROUP BY 1,2
            ), actual AS(SELECT * FROM daily_account_summary WHERE account_id IN (?,?))
            SELECT count(*) FROM expected e FULL JOIN actual d USING(account_id,summary_date)
            WHERE e.account_id IS NULL OR d.account_id IS NULL OR d.opening_balance_minor<>e.opening
                OR d.closing_balance_minor<>e.closing OR d.total_inflow_minor<>e.inflow
                OR d.total_outflow_minor<>e.outflow OR d.posting_count<>e.postings
            """).params(from,to,from,to).query(Long.class).single());
        String versions=jdbc.sql("SELECT string_agg(id||':'||xmin::text,',' ORDER BY id) FROM statement_view WHERE account_id=?")
            .param(from).query(String.class).single();
        assertEquals(0,projectionService.projectBatch(events));
        assertEquals(versions,jdbc.sql("SELECT string_agg(id||':'||xmin::text,',' ORDER BY id) FROM statement_view WHERE account_id=?")
            .param(from).query(String.class).single());
    }

    @Test
    void batchOverflowRollsBackEarlierEventsAndEveryMarker() {
        UUID to=UUID.randomUUID(),from=UUID.randomUUID();
        Instant now=Instant.parse("2026-10-20T10:00:00Z");
        projectionService.projectTransfer(new TransferEventPayload(UUID.randomUUID(),UUID.randomUUID(),from,to,Long.MAX_VALUE,"USD",now));
        var valid=new TransferEventPayload(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),5,"USD",now.plusSeconds(1));
        var overflow=new TransferEventPayload(UUID.randomUUID(),UUID.randomUUID(),from,to,1,"USD",now.plusSeconds(2));
        assertThrows(ArithmeticException.class,()->projectionService.projectBatch(List.of(valid,overflow)));
        assertFalse(processedEventsRepository.isProcessed(valid.eventId()));
        assertFalse(processedEventsRepository.isProcessed(overflow.eventId()));
        assertTrue(projectionService.getStatement(valid.fromAccountId(),10,0).isEmpty());
        assertEquals(Long.MAX_VALUE,projectionService.getStatement(to,1,0).getFirst().runningBalanceMinor());
    }

    @Test
    void mixedAppendAndLateArrivalsMatchBothSidesOfFullHistoryOracle() {
        UUID from = UUID.randomUUID(), to = UUID.randomUUID();
        Instant base = Instant.parse("2026-10-20T23:59:59.999999900Z");
        var random = new java.util.Random(7319);
        for (int i = 0; i < 80; i++) {
            boolean reverse = random.nextBoolean();
            // Mix appends, late events, equal timestamps, and UTC microsecond rounding.
            Instant time = base.plusSeconds(i % 4 == 0 ? random.nextInt(i + 1) : i)
                .plusSeconds(i / 25 * 86400L);
            assertTrue(projectionService.projectTransfer(new TransferEventPayload(UUID.randomUUID(), UUID.randomUUID(),
                reverse ? to : from, reverse ? from : to, 1 + random.nextInt(100), "USD", time)));
            assertEquals(0L, jdbc.sql("""
                WITH rows AS (
                    SELECT *, sum(amount_minor) OVER (PARTITION BY account_id ORDER BY created_at,id) expected
                    FROM statement_view WHERE account_id IN (?,?)
                ) SELECT count(*) FROM rows WHERE running_balance_minor<>expected
                """).params(from, to).query(Long.class).single());
            assertEquals(0L, jdbc.sql("""
                WITH expected AS (
                    SELECT account_id,(created_at AT TIME ZONE 'UTC')::date summary_date,
                        (array_agg(running_balance_minor-amount_minor ORDER BY created_at,id))[1] opening,
                        (array_agg(running_balance_minor ORDER BY created_at DESC,id DESC))[1] closing,
                        coalesce(sum(amount_minor) FILTER (WHERE amount_minor>0),0) inflow,
                        coalesce(sum(-amount_minor) FILTER (WHERE amount_minor<0),0) outflow,count(*) postings
                    FROM statement_view WHERE account_id IN (?,?) GROUP BY 1,2
                ), actual AS (SELECT * FROM daily_account_summary WHERE account_id IN (?,?))
                SELECT count(*) FROM expected e FULL JOIN actual d USING(account_id,summary_date)
                WHERE e.account_id IS NULL OR d.account_id IS NULL OR d.opening_balance_minor<>e.opening
                    OR d.closing_balance_minor<>e.closing OR d.total_inflow_minor<>e.inflow
                    OR d.total_outflow_minor<>e.outflow OR d.posting_count<>e.postings
                """).params(from, to, from, to).query(Long.class).single());
        }
    }

    @Autowired
    private ProjectionService projectionService;

    @Autowired
    private ProcessedEventsRepository processedEventsRepository;

    @Autowired
    private org.springframework.jdbc.core.simple.JdbcClient jdbc;

    @Test
    void incrementalProjectionMatchesFullHistoryOracleAcrossDaysAndEqualTimestamps() {
        UUID account = UUID.randomUUID(), other = UUID.randomUUID();
        Instant base = Instant.parse("2026-10-20T23:59:59Z");
        int[] arrivalOrder = {4, 0, 3, 1, 2, 2, -1};
        for (int position : arrivalOrder) {
            Instant timestamp = base.plusSeconds(position);
            assertTrue(projectionService.projectTransfer(new TransferEventPayload(UUID.randomUUID(), UUID.randomUUID(),
                other, account, 10, "USD", timestamp)));
            assertEquals(0L, jdbc.sql("""
                SELECT count(*) FROM (
                    SELECT running_balance_minor, sum(amount_minor) OVER (ORDER BY created_at,id) expected
                    FROM statement_view WHERE account_id = ?
                ) checked WHERE running_balance_minor <> expected
                """).param(account).query(Long.class).single());
            assertEquals(0L, jdbc.sql("""
                WITH expected AS (
                    SELECT (created_at AT TIME ZONE 'UTC')::date AS summary_date,
                        (array_agg(running_balance_minor - amount_minor ORDER BY created_at,id))[1] opening,
                        (array_agg(running_balance_minor ORDER BY created_at DESC,id DESC))[1] closing,
                        sum(amount_minor) FILTER (WHERE amount_minor > 0) inflow, count(*) count
                    FROM statement_view WHERE account_id = ? GROUP BY 1
                ) SELECT count(*) FROM expected e JOIN daily_account_summary d ON d.summary_date=e.summary_date
                WHERE d.account_id=? AND (d.opening_balance_minor<>e.opening OR d.closing_balance_minor<>e.closing
                    OR d.total_inflow_minor<>e.inflow OR d.posting_count<>e.count)
                """).params(account, account).query(Long.class).single());
        }
    }

    @Test
    void inOrderEventDoesNotRewriteEarlierHistoryAndReplayChangesNothing() {
        UUID account = UUID.randomUUID(), other = UUID.randomUUID();
        Instant base = Instant.parse("2026-10-20T10:00:00Z");
        var first = new TransferEventPayload(UUID.randomUUID(), UUID.randomUUID(), other, account, 100, "USD", base);
        assertTrue(projectionService.projectTransfer(first));
        String rowVersion = jdbc.sql("SELECT xmin::text FROM statement_view WHERE account_id=?")
            .param(account).query(String.class).single();
        for (int i = 1; i <= 100; i++) {
            assertTrue(projectionService.projectTransfer(new TransferEventPayload(UUID.randomUUID(), UUID.randomUUID(),
                account, other, 1, "USD", base.plusSeconds(i))));
        }
        assertEquals(rowVersion, jdbc.sql("SELECT xmin::text FROM statement_view WHERE account_id=? AND transaction_id=?")
            .params(account, first.transactionId()).query(String.class).single(), "In-order events must not rewrite old history");
        assertFalse(projectionService.projectTransfer(first));
        assertEquals(101, projectionService.getDailySummary(account, LocalDate.parse("2026-10-20")).orElseThrow().postingCount());
        assertEquals(0, projectionService.getStatement(account, 1, 0).getFirst().runningBalanceMinor());
    }

    @Test
    void usesPersistedMicrosecondTimestampForUtcDailySummary() {
        UUID from = UUID.randomUUID(), to = UUID.randomUUID();
        assertTrue(projectionService.projectTransfer(new TransferEventPayload(UUID.randomUUID(), UUID.randomUUID(),
            from, to, 1, "USD", Instant.parse("2026-10-20T23:59:59.999999900Z"))));
        LocalDate storedDay = jdbc.sql("SELECT (created_at AT TIME ZONE 'UTC')::date FROM statement_view WHERE account_id=?")
            .param(to).query(java.sql.Date.class).single().toLocalDate();
        assertEquals(1, projectionService.getDailySummary(to, storedDay).orElseThrow().closingBalanceMinor());
        assertEquals(-1, projectionService.getDailySummary(from, storedDay).orElseThrow().closingBalanceMinor());
    }

    @Test
    void overflowRollsBackBothAccountsAndProcessedEventMarker() {
        UUID source = UUID.randomUUID(), destination = UUID.randomUUID();
        Instant time = Instant.parse("2026-10-20T10:00:00Z");
        assertTrue(projectionService.projectTransfer(new TransferEventPayload(UUID.randomUUID(), UUID.randomUUID(),
            UUID.randomUUID(), destination, Long.MAX_VALUE, "USD", time)));
        var overflow = new TransferEventPayload(UUID.randomUUID(), UUID.randomUUID(), source, destination,
            1, "USD", time.plusSeconds(1));
        assertThrows(ArithmeticException.class, () -> projectionService.projectTransfer(overflow));
        assertFalse(processedEventsRepository.isProcessed(overflow.eventId()));
        assertTrue(projectionService.getStatement(source, 10, 0).isEmpty());
        assertTrue(projectionService.getDailySummary(source, LocalDate.parse("2026-10-20")).isEmpty());
        assertEquals(1, projectionService.getStatement(destination, 10, 0).size());
        assertEquals(Long.MAX_VALUE, projectionService.getStatement(destination, 1, 0).getFirst().runningBalanceMinor());
    }

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

    @Test
    @DisplayName("Funding immediately followed by customer debit remains ordered when Kafka delivers the debit first")
    void shouldRebuildStatementInEventTimeOrderWhenDebitArrivesBeforeFunding() {
        UUID checking = UUID.randomUUID();
        UUID funding = UUID.randomUUID();
        UUID savings = UUID.randomUUID();
        Instant fundingTime = Instant.parse("2026-10-01T10:00:00Z");
        Instant debitTime = fundingTime.plusMillis(1);

        TransferEventPayload fundingEvent = new TransferEventPayload(
            UUID.randomUUID(), UUID.randomUUID(), funding, checking, 100_000L, "USD", fundingTime
        );
        TransferEventPayload debitEvent = new TransferEventPayload(
            UUID.randomUUID(), UUID.randomUUID(), checking, savings, 12_500L, "USD", debitTime
        );

        // Reproduce the real cross-partition race: the later debit is consumed first.
        assertTrue(projectionService.projectTransfer(debitEvent));
        assertTrue(projectionService.projectTransfer(fundingEvent));

        List<StatementViewEntry> statement = projectionService.getStatement(checking, 10, 0);
        assertEquals(2, statement.size());
        assertEquals(debitEvent.transactionId(), statement.get(0).transactionId());
        assertEquals(-12_500L, statement.get(0).amountMinor());
        assertEquals(87_500L, statement.get(0).runningBalanceMinor());
        assertEquals(fundingEvent.transactionId(), statement.get(1).transactionId());
        assertEquals(100_000L, statement.get(1).amountMinor());
        assertEquals(100_000L, statement.get(1).runningBalanceMinor());

        DailyAccountSummary summary = projectionService
            .getDailySummary(checking, fundingTime.atZone(ZoneOffset.UTC).toLocalDate())
            .orElseThrow();
        assertEquals(0L, summary.openingBalanceMinor());
        assertEquals(87_500L, summary.closingBalanceMinor());
        assertEquals(100_000L, summary.totalInflowMinor());
        assertEquals(12_500L, summary.totalOutflowMinor());
        assertEquals(2, summary.postingCount());
    }
}
