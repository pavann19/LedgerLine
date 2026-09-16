package com.ledgerline.ledger.service.strategy;

import com.ledgerline.ledger.domain.AccountBalance;
import com.ledgerline.ledger.domain.AccountType;
import com.ledgerline.ledger.domain.IsolationVariant;
import com.ledgerline.ledger.dto.TransferRequest;
import com.ledgerline.ledger.dto.TransferResponse;
import com.ledgerline.ledger.exception.AccountNotFoundException;
import com.ledgerline.ledger.exception.InsufficientFundsException;
import com.ledgerline.ledger.repository.AccountBalanceRepository;
import com.ledgerline.ledger.service.TransferCoreSupport;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

@Component
public class SerializableTransferStrategy implements TransferExecutionStrategy {

    private final TransferCoreSupport support;
    private final AccountBalanceRepository balanceRepository;
    private final TransactionTemplate serializableTemplate;
    private static final int MAX_RETRIES = 5;

    public SerializableTransferStrategy(
        TransferCoreSupport support,
        AccountBalanceRepository balanceRepository,
        PlatformTransactionManager transactionManager
    ) {
        this.support = support;
        this.balanceRepository = balanceRepository;
        this.serializableTemplate = new TransactionTemplate(transactionManager);
        this.serializableTemplate.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);
    }

    @Override
    public IsolationVariant getVariant() {
        return IsolationVariant.VARIANT_3_SERIALIZABLE;
    }

    @Override
    public TransferResponse execute(String idempotencyKey, TransferRequest request, byte[] requestHash) {
        Optional<TransferResponse> cached = support.checkIdempotency(idempotencyKey, requestHash);
        if (cached.isPresent()) {
            return cached.get();
        }

        support.validateTransferRequest(request);

        int attempts = 0;
        while (attempts < MAX_RETRIES) {
            attempts++;
            try {
                return serializableTemplate.execute(status -> {
                    Optional<TransferResponse> replay = support.checkIdempotency(idempotencyKey, requestHash);
                    if (replay.isPresent()) {
                        return replay.get();
                    }

                    AccountBalance fromBalance = balanceRepository.findById(request.fromAccountId())
                        .orElseThrow(() -> new AccountNotFoundException(request.fromAccountId()));
                    AccountBalance toBalance = balanceRepository.findById(request.toAccountId())
                        .orElseThrow(() -> new AccountNotFoundException(request.toAccountId()));

                    if (fromBalance.accountType() != AccountType.OVERDRAFT && fromBalance.balanceMinor() < request.amountMinor()) {
                        throw new InsufficientFundsException(String.format(
                            "Insufficient funds in account %s: available %d, required %d",
                            request.fromAccountId(), fromBalance.balanceMinor(), request.amountMinor()
                        ));
                    }

                    UUID transactionId = UUID.randomUUID();
                    Instant now = Instant.now();

                    TransferResponse response = support.recordTransactionAndPostings(
                        transactionId,
                        idempotencyKey,
                        requestHash,
                        request,
                        now
                    );

                    balanceRepository.updateBalance(new AccountBalance(
                        request.fromAccountId(),
                        fromBalance.balanceMinor() - request.amountMinor(),
                        fromBalance.accountType(),
                        fromBalance.version() + 1
                    ));

                    balanceRepository.updateBalance(new AccountBalance(
                        request.toAccountId(),
                        toBalance.balanceMinor() + request.amountMinor(),
                        toBalance.accountType(),
                        toBalance.version() + 1
                    ));

                    return response;
                });
            } catch (Exception e) {
                if (isSerializationFailure(e)) {
                    if (attempts >= MAX_RETRIES) {
                        throw new RuntimeException("Exceeded maximum SERIALIZABLE retries (" + MAX_RETRIES + ") on 40001", e);
                    }
                    try {
                        long backoff = (1L << attempts) * 15 + ThreadLocalRandom.current().nextLong(5, 25);
                        Thread.sleep(backoff);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Interrupted during serializable retry", ie);
                    }
                } else if (e instanceof DataIntegrityViolationException) {
                    Optional<TransferResponse> replay = support.checkIdempotency(idempotencyKey, requestHash);
                    if (replay.isPresent()) {
                        return replay.get();
                    }
                    throw e;
                } else {
                    throw e;
                }
            }
        }

        throw new RuntimeException("Exceeded maximum SERIALIZABLE retries (" + MAX_RETRIES + ")");
    }

    private boolean isSerializationFailure(Throwable t) {
        while (t != null) {
            if (t instanceof SQLException sqlEx) {
                String state = sqlEx.getSQLState();
                if ("40001".equals(state) || "40P01".equals(state)) {
                    return true;
                }
            }
            t = t.getCause();
        }
        return false;
    }
}
