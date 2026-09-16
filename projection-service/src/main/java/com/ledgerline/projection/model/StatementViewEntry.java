package com.ledgerline.projection.model;

import java.time.Instant;
import java.util.UUID;

public record StatementViewEntry(
    Long id,
    UUID accountId,
    UUID transactionId,
    long amountMinor,
    long runningBalanceMinor,
    String currency,
    Instant createdAt
) {}
