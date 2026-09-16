package com.ledgerline.ledger.domain;

import java.util.UUID;

public record AccountBalance(
    UUID accountId,
    long balanceMinor,
    AccountType accountType,
    long version
) {
    public AccountBalance withDelta(long delta) {
        return new AccountBalance(accountId, balanceMinor + delta, accountType, version + 1);
    }
}
