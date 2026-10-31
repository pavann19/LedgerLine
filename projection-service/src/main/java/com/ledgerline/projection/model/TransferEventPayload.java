package com.ledgerline.projection.model;

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
    public TransferEventPayload {
        if (eventId == null || transactionId == null || fromAccountId == null || toAccountId == null
            || fromAccountId.equals(toAccountId) || amountMinor <= 0 || timestamp == null
            || currency == null || !currency.matches("[A-Z]{3}")) {
            throw new IllegalArgumentException("Invalid transfer event");
        }
        if (correlationId == null) correlationId=transactionId.toString();
        if (!correlationId.matches("[A-Za-z0-9-]{1,64}")) throw new IllegalArgumentException("Invalid correlation ID");
    }
}
