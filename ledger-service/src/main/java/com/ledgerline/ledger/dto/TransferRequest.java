package com.ledgerline.ledger.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record TransferRequest(
    @NotNull(message = "fromAccountId is required")
    UUID fromAccountId,

    @NotNull(message = "toAccountId is required")
    UUID toAccountId,

    @Positive(message = "amountMinor must be strictly positive")
    long amountMinor,

    @NotBlank
    @Size(min = 3, max = 3, message = "Currency must be 3-character ISO-4217 code")
    String currency
) {}
