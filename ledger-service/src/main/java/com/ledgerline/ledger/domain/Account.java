package com.ledgerline.ledger.domain;

import java.time.Instant;
import java.util.UUID;

public record Account(
    UUID id,
    String currency,
    AccountType type,
    AccountStatus status,
    Instant createdAt,
    long version,
    String ownerPrincipal
) {}
