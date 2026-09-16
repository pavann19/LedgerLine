package com.ledgerline.ledger.dto;

import com.ledgerline.ledger.domain.TransactionStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record TransferResponse(
    UUID transactionId,
    String idempotencyKey,
    TransactionStatus status,
    Instant createdAt,
    List<PostingDto> postings,
    boolean idempotentReplay
) {}
