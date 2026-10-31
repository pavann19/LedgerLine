package com.ledgerline.ledger.dto;

import java.time.Instant;
import java.util.UUID;

public record TransferEventPayload(
    UUID eventId,
    UUID transactionId,
    UUID fromAccountId,
    UUID toAccountId,
    long amountMinor,
    String currency,
    Instant timestamp,
    String correlationId
) {
    public TransferEventPayload(UUID eventId,UUID transactionId,UUID fromAccountId,UUID toAccountId,long amountMinor,String currency,Instant timestamp) {
        this(eventId,transactionId,fromAccountId,toAccountId,amountMinor,currency,timestamp,transactionId.toString());
    }
}
