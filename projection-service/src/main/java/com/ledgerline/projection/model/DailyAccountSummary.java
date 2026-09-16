package com.ledgerline.projection.model;

import java.time.LocalDate;
import java.util.UUID;

public record DailyAccountSummary(
    UUID accountId,
    LocalDate summaryDate,
    long openingBalanceMinor,
    long closingBalanceMinor,
    long totalInflowMinor,
    long totalOutflowMinor,
    int postingCount
) {}
