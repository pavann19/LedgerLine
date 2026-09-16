package com.ledgerline.ledger.domain;

import java.time.Instant;
import java.util.UUID;

public record OutboxEvent(
    Long id,
    UUID aggregateId,
    String eventType,
    String payload,
    Instant createdAt,
    Instant publishedAt
) {}
