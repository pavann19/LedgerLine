package com.ledgerline.ledger.dto;

import java.time.Instant;
import java.util.UUID;

public record PostingDto(
    Long id,
    UUID accountId,
    long amountMinor,
    String currency,
    Instant createdAt
) {}
