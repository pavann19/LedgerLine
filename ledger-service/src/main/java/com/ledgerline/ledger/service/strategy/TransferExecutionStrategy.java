package com.ledgerline.ledger.service.strategy;

import com.ledgerline.ledger.domain.IsolationVariant;
import com.ledgerline.ledger.dto.TransferRequest;
import com.ledgerline.ledger.dto.TransferResponse;

public interface TransferExecutionStrategy {
    TransferResponse execute(String idempotencyKey, TransferRequest request, byte[] requestHash);
    IsolationVariant getVariant();
}
