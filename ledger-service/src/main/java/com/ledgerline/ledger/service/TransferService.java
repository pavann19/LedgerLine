package com.ledgerline.ledger.service;

import com.ledgerline.ledger.domain.IsolationVariant;
import com.ledgerline.ledger.dto.TransferRequest;
import com.ledgerline.ledger.dto.TransferResponse;
import com.ledgerline.ledger.exception.IdempotencyConflictException;
import com.ledgerline.ledger.exception.InsufficientFundsException;
import com.ledgerline.ledger.service.strategy.TransferExecutionStrategy;
import com.ledgerline.ledger.repository.AccountRepository;
import com.ledgerline.ledger.exception.AccountNotFoundException;
import org.springframework.security.access.AccessDeniedException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Service
public class TransferService {

    private static final Logger log = LoggerFactory.getLogger(TransferService.class);

    private final Map<IsolationVariant, TransferExecutionStrategy> strategies = new EnumMap<>(IsolationVariant.class);
    private final RequestHasher requestHasher;
    private final MeterRegistry meterRegistry;
    private final IsolationVariant defaultVariant;
    private final AccountRepository accountRepository;

    public TransferService(
        List<TransferExecutionStrategy> strategyList,
        RequestHasher requestHasher,
        MeterRegistry meterRegistry,
        AccountRepository accountRepository,
        @Value("${ledger.transfer.default-variant:VARIANT_1_PESSIMISTIC}") String defaultVariantName
    ) {
        for (TransferExecutionStrategy strategy : strategyList) {
            strategies.put(strategy.getVariant(), strategy);
        }
        this.requestHasher = requestHasher;
        this.meterRegistry = meterRegistry;
        this.accountRepository = accountRepository;
        this.defaultVariant = IsolationVariant.valueOf(defaultVariantName);
    }

    public TransferResponse transfer(
        String idempotencyKey,
        TransferRequest request,
        IsolationVariant requestedVariant
    ) {
        return transfer(idempotencyKey, request, requestedVariant, "SYSTEM", true);
    }

    public TransferResponse transfer(
        String idempotencyKey,
        TransferRequest request,
        IsolationVariant requestedVariant,
        String principalId,
        boolean operator
    ) {
        var from = accountRepository.findById(request.fromAccountId()).orElseThrow(() -> new AccountNotFoundException(request.fromAccountId()));
        if (!operator && !from.ownerPrincipal().equals(principalId)) {
            throw new AccessDeniedException("Source account is not owned by the authenticated principal");
        }
        String scopedKey = principalId.equals("SYSTEM") ? idempotencyKey : principalId + '\u001F' + idempotencyKey;
        IsolationVariant variant = requestedVariant != null ? requestedVariant : defaultVariant;
        TransferExecutionStrategy strategy = strategies.get(variant);
        if (strategy == null) {
            throw new IllegalArgumentException("Unsupported isolation variant: " + variant);
        }

        byte[] requestHash = requestHasher.computeHash(request);
        Timer.Sample sample = Timer.start(meterRegistry);

        try {
            TransferResponse response = strategy.execute(scopedKey, request, requestHash);
            String outcome = response.idempotentReplay() ? "idempotent_replay" : "success";
            sample.stop(meterRegistry.timer("ledger.transfers.latency", "variant", variant.name(), "outcome", outcome));
            meterRegistry.counter("ledger.transfers.outcomes", "variant", variant.name(), "outcome", outcome).increment();
            return new TransferResponse(response.transactionId(), idempotencyKey, response.status(), response.createdAt(), response.postings(), response.idempotentReplay());
        } catch (InsufficientFundsException e) {
            sample.stop(meterRegistry.timer("ledger.transfers.latency", "variant", variant.name(), "outcome", "insufficient_funds"));
            meterRegistry.counter("ledger.transfers.outcomes", "variant", variant.name(), "outcome", "insufficient_funds").increment();
            throw e;
        } catch (IdempotencyConflictException e) {
            sample.stop(meterRegistry.timer("ledger.transfers.latency", "variant", variant.name(), "outcome", "idempotency_conflict"));
            meterRegistry.counter("ledger.transfers.outcomes", "variant", variant.name(), "outcome", "idempotency_conflict").increment();
            throw e;
        } catch (Exception e) {
            sample.stop(meterRegistry.timer("ledger.transfers.latency", "variant", variant.name(), "outcome", "error"));
            meterRegistry.counter("ledger.transfers.outcomes", "variant", variant.name(), "outcome", "error").increment();
            throw e;
        }
    }
}
