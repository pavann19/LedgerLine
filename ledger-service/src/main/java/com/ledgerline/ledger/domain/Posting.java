package com.ledgerline.ledger.domain;

import java.time.Instant;
import java.util.UUID;

public record Posting(
    Long id,
    UUID transactionId,
    UUID accountId,
    long amountMinor,
    String currency,
    Instant createdAt
) {}
