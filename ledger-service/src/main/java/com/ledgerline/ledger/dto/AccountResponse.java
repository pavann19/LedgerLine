package com.ledgerline.ledger.dto;

import com.ledgerline.ledger.domain.AccountStatus;
import com.ledgerline.ledger.domain.AccountType;

import java.time.Instant;
import java.util.UUID;

public record AccountResponse(
    UUID id,
    String currency,
    AccountType type,
    AccountStatus status,
    Instant createdAt,
    long balanceMinor
) {}
