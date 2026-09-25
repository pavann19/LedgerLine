package com.ledgerline.ledger.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerline.ledger.domain.*;
import com.ledgerline.ledger.dto.PostingDto;
import com.ledgerline.ledger.dto.TransferEventPayload;
import com.ledgerline.ledger.dto.TransferRequest;
import com.ledgerline.ledger.dto.TransferResponse;
import com.ledgerline.ledger.exception.AccountNotFoundException;
import com.ledgerline.ledger.exception.IdempotencyConflictException;
import com.ledgerline.ledger.exception.InvalidTransferException;
import com.ledgerline.ledger.repository.AccountRepository;
import com.ledgerline.ledger.repository.AuditLogRepository;
import com.ledgerline.ledger.repository.OutboxRepository;
import com.ledgerline.ledger.repository.PostingRepository;
import com.ledgerline.ledger.repository.TransactionRepository;
import org.springframework.stereotype.Component;
import com.ledgerline.ledger.security.CurrentPrincipal;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
public class TransferCoreSupport {

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final PostingRepository postingRepository;
    private final OutboxRepository outboxRepository;
    private final AuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper;
    private final CurrentPrincipal currentPrincipal;

    public TransferCoreSupport(
        AccountRepository accountRepository,
        TransactionRepository transactionRepository,
        PostingRepository postingRepository,
        OutboxRepository outboxRepository,
        AuditLogRepository auditLogRepository,
        ObjectMapper objectMapper,
        CurrentPrincipal currentPrincipal
    ) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.postingRepository = postingRepository;
        this.outboxRepository = outboxRepository;
        this.auditLogRepository = auditLogRepository;
        this.objectMapper = objectMapper;
        this.currentPrincipal = currentPrincipal;
    }

    public void validateTransferRequest(TransferRequest request) {
        if (request.fromAccountId().equals(request.toAccountId())) {
            throw new InvalidTransferException("Source and destination accounts must be distinct");
        }
        if (request.amountMinor() <= 0) {
            throw new InvalidTransferException("Transfer amount must be strictly positive");
        }

        Account fromAccount = accountRepository.findById(request.fromAccountId())
            .orElseThrow(() -> new AccountNotFoundException(request.fromAccountId()));
        Account toAccount = accountRepository.findById(request.toAccountId())
            .orElseThrow(() -> new AccountNotFoundException(request.toAccountId()));

        if (fromAccount.status() != AccountStatus.ACTIVE) {
            throw new InvalidTransferException("Source account is not ACTIVE: " + fromAccount.status());
        }
        if (toAccount.status() != AccountStatus.ACTIVE) {
            throw new InvalidTransferException("Destination account is not ACTIVE: " + toAccount.status());
        }

        String reqCurrency = request.currency().trim().toUpperCase();
        if (!fromAccount.currency().equalsIgnoreCase(reqCurrency) || !toAccount.currency().equalsIgnoreCase(reqCurrency)) {
            throw new InvalidTransferException(
                String.format("Currency mismatch: request=%s, from=%s, to=%s",
                    reqCurrency, fromAccount.currency(), toAccount.currency())
            );
        }
    }

    public Optional<TransferResponse> checkIdempotency(String idempotencyKey, byte[] requestHash) {
        Optional<Transaction> existing = transactionRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isEmpty()) {
            return Optional.empty();
        }

        Transaction tx = existing.get();
        if (!Arrays.equals(tx.requestHash(), requestHash)) {
            throw new IdempotencyConflictException("Idempotency key reused with different request payload");
        }

        List<Posting> postings = postingRepository.findByTransactionId(tx.id());
        List<PostingDto> postingDtos = postings.stream()
            .map(p -> new PostingDto(p.id(), p.accountId(), p.amountMinor(), p.currency(), p.createdAt()))
            .toList();

        return Optional.of(new TransferResponse(
            tx.id(),
            tx.idempotencyKey(),
            tx.status(),
            tx.createdAt(),
            postingDtos,
            true // Idempotent replay
        ));
    }

    public TransferResponse recordTransactionAndPostings(
        UUID transactionId,
        String idempotencyKey,
        byte[] requestHash,
        TransferRequest request,
        Instant now
    ) {
        // 1. Insert Transaction
        Transaction tx = new Transaction(
            transactionId,
            idempotencyKey,
            requestHash,
            TransactionStatus.POSTED,
            now
        );
        transactionRepository.insertTransaction(tx);

        // 2. Insert Balanced Double-Entry Postings (Zero-Sum Invariant: -amount + amount = 0)
        String currency = request.currency().trim().toUpperCase();
        Posting debitPosting = new Posting(
            null,
            transactionId,
            request.fromAccountId(),
            -request.amountMinor(),
            currency,
            now
        );
        Posting creditPosting = new Posting(
            null,
            transactionId,
            request.toAccountId(),
            request.amountMinor(),
            currency,
            now
        );
        postingRepository.insertPosting(debitPosting);
        postingRepository.insertPosting(creditPosting);

        // 3. Insert Outbox Event for Async Kafka Relay
        UUID eventId = UUID.randomUUID();
        TransferEventPayload payload = new TransferEventPayload(
            eventId,
            transactionId,
            request.fromAccountId(),
            request.toAccountId(),
            request.amountMinor(),
            currency,
            now
        );
        try {
            String payloadJson = objectMapper.writeValueAsString(payload);
            outboxRepository.insertEvent(new OutboxEvent(
                null,
                transactionId,
                "TransferPosted",
                payloadJson,
                now,
                null
            ));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize outbox event payload", e);
        }

        // 4. Insert Audit Log
        auditLogRepository.insertLog(new AuditLogEntry(
            null,
            currentPrincipal.id(),
            "TRANSFER_POSTED",
            transactionId.toString(),
            now,
            String.format("{\"from\":\"%s\",\"to\":\"%s\",\"amount\":%d,\"currency\":\"%s\"}",
                request.fromAccountId(), request.toAccountId(), request.amountMinor(), currency)
        ));

        // 5. Query created postings with their generated IDs
        List<Posting> persistedPostings = postingRepository.findByTransactionId(transactionId);
        List<PostingDto> postingDtos = persistedPostings.stream()
            .map(p -> new PostingDto(p.id(), p.accountId(), p.amountMinor(), p.currency(), p.createdAt()))
            .toList();

        return new TransferResponse(
            transactionId,
            idempotencyKey,
            TransactionStatus.POSTED,
            now,
            postingDtos,
            false // Fresh transaction
        );
    }
}
