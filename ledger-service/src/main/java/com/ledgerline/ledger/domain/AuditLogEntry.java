package com.ledgerline.ledger.domain;

import java.time.Instant;

public record AuditLogEntry(
    Long id,
    String actor,
    String action,
    String entityId,
    Instant at,
    String details
) {}
