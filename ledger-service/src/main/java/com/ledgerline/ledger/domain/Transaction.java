package com.ledgerline.ledger.domain;

import java.time.Instant;
import java.util.UUID;

public record Transaction(
    UUID id,
    String idempotencyKey,
    byte[] requestHash,
    TransactionStatus status,
    Instant createdAt
) {}
