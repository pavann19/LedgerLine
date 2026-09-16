package com.ledgerline.ledger.dto;

import com.ledgerline.ledger.domain.AccountType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateAccountRequest(
    @NotBlank
    @Size(min = 3, max = 3, message = "Currency must be 3-character ISO-4217 code")
    String currency,

    @NotNull
    AccountType type
) {}
