package com.ledgerline.ledger.service;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import com.ledgerline.ledger.domain.*;
import com.ledgerline.ledger.dto.PostingDto;
import com.ledgerline.ledger.dto.TransferEventPayload;
import com.ledgerline.ledger.dto.TransferRequest;
import com.ledgerline.ledger.dto.TransferResponse;
import com.ledgerline.ledger.exception.AccountNotFoundException;
import com.ledgerline.ledger.exception.IdempotencyConflictException;
import com.ledgerline.ledger.exception.InvalidTransferException;
import com.ledgerline.ledger.repository.AccountRepository;
import com.ledgerline.ledger.repository.TransferWriteRepository;
import com.ledgerline.ledger.repository.PostingRepository;
import com.ledgerline.ledger.repository.TransactionRepository;
import org.springframework.stereotype.Component;
import com.ledgerline.ledger.security.CurrentPrincipal;

import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Component
public class TransferCoreSupport {

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final PostingRepository postingRepository;
    private final TransferWriteRepository transferWriteRepository;
    private final ObjectMapper objectMapper;
    private final CurrentPrincipal currentPrincipal;

    public TransferCoreSupport(
        AccountRepository accountRepository,
        TransactionRepository transactionRepository,
        PostingRepository postingRepository,
        TransferWriteRepository transferWriteRepository,
        ObjectMapper objectMapper,
        CurrentPrincipal currentPrincipal
    ) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.postingRepository = postingRepository;
        this.transferWriteRepository = transferWriteRepository;
        this.objectMapper = objectMapper;
        this.currentPrincipal = currentPrincipal;
    }

    public void validateLockedTransferRequest(TransferRequest request) {
        Map<UUID, Account> lockedAccounts = new HashMap<>();
        java.util.stream.Stream.of(request.fromAccountId(), request.toAccountId()).distinct().sorted()
            .forEach(id -> lockedAccounts.put(id, accountRepository.findByIdForShare(id)
                .orElseThrow(() -> new AccountNotFoundException(id))));
        validateTransferShape(request);
        validateAccounts(request, lockedAccounts.get(request.fromAccountId()),
            lockedAccounts.get(request.toAccountId()));
    }

    public void validateTransferRequest(TransferRequest request) {
        validateTransferShape(request);

        Account fromAccount = accountRepository.findById(request.fromAccountId())
            .orElseThrow(() -> new AccountNotFoundException(request.fromAccountId()));
        Account toAccount = accountRepository.findById(request.toAccountId())
            .orElseThrow(() -> new AccountNotFoundException(request.toAccountId()));
        validateAccounts(request, fromAccount, toAccount);
    }

    private void validateTransferShape(TransferRequest request) {
        if (request.fromAccountId().equals(request.toAccountId())) {
            throw new InvalidTransferException("Source and destination accounts must be distinct");
        }
        if (request.amountMinor() <= 0) {
            throw new InvalidTransferException("Transfer amount must be strictly positive");
        }
    }

    private void validateAccounts(TransferRequest request, Account fromAccount, Account toAccount) {
        if (fromAccount.status() != AccountStatus.ACTIVE) {
            throw new InvalidTransferException("Source account is not ACTIVE: " + fromAccount.status());
        }
        if (toAccount.status() != AccountStatus.ACTIVE) {
            throw new InvalidTransferException("Destination account is not ACTIVE: " + toAccount.status());
        }

        String reqCurrency = request.currency().trim().toUpperCase(java.util.Locale.ROOT);
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

    public void lockIdempotencyKey(String scopedKey) {
        transactionRepository.lockIdempotencyKey(scopedKey);
    }

    public TransferResponse recordTransactionAndPostings(
        UUID transactionId,
        String idempotencyKey,
        byte[] requestHash,
        TransferRequest request,
        Instant now
    ) {
        String currency = request.currency().trim().toUpperCase(java.util.Locale.ROOT);
        var payload = new TransferEventPayload(UUID.randomUUID(), transactionId, request.fromAccountId(),
            request.toAccountId(), request.amountMinor(), currency, now,
            java.util.Optional.ofNullable(org.slf4j.MDC.get("request_id")).orElse(transactionId.toString()));
        String payloadJson;
        try {
            payloadJson = objectMapper.writeValueAsString(payload);
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to serialize outbox event payload", e);
        }
        String details = String.format("{\"from\":\"%s\",\"to\":\"%s\",\"amount\":%d,\"currency\":\"%s\"}",
            request.fromAccountId(), request.toAccountId(), request.amountMinor(), currency);
        List<Posting> persistedPostings = transferWriteRepository.insert(transactionId, idempotencyKey,
            requestHash, request.fromAccountId(), request.toAccountId(), request.amountMinor(), currency,
            now, payloadJson, currentPrincipal.id(), details);

        // 5. Return the stored IDs and timestamp precision from INSERT ... RETURNING.
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
