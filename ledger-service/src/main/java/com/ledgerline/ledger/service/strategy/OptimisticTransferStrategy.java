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
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

@Component
public class OptimisticTransferStrategy implements TransferExecutionStrategy {

    private final TransferCoreSupport support;
    private final AccountBalanceRepository balanceRepository;
    private final TransactionTemplate transactionTemplate;
    private static final int MAX_RETRIES = 5;

    public OptimisticTransferStrategy(
        TransferCoreSupport support,
        AccountBalanceRepository balanceRepository,
        PlatformTransactionManager transactionManager
    ) {
        this.support = support;
        this.balanceRepository = balanceRepository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public IsolationVariant getVariant() {
        return IsolationVariant.VARIANT_2_OPTIMISTIC;
    }

    @Override
    public TransferResponse execute(String idempotencyKey, TransferRequest request, byte[] requestHash) {
        // Fast-path idempotency check
        Optional<TransferResponse> cached = support.checkIdempotency(idempotencyKey, requestHash);
        if (cached.isPresent()) {
            return cached.get();
        }

        support.validateTransferRequest(request);

        int attempts = 0;
        while (attempts < MAX_RETRIES) {
            attempts++;
            try {
                return transactionTemplate.execute(status -> {
                    // Re-check idempotency inside transaction
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

                    int fromUpdated = balanceRepository.updateBalanceOptimistic(
                        request.fromAccountId(),
                        fromBalance.balanceMinor() - request.amountMinor(),
                        fromBalance.version()
                    );
                    if (fromUpdated == 0) {
                        throw new OptimisticLockingFailureException("Optimistic lock conflict on fromAccount: " + request.fromAccountId());
                    }

                    int toUpdated = balanceRepository.updateBalanceOptimistic(
                        request.toAccountId(),
                        toBalance.balanceMinor() + request.amountMinor(),
                        toBalance.version()
                    );
                    if (toUpdated == 0) {
                        throw new OptimisticLockingFailureException("Optimistic lock conflict on toAccount: " + request.toAccountId());
                    }

                    return response;
                });
            } catch (OptimisticLockingFailureException e) {
                if (attempts >= MAX_RETRIES) {
                    throw e;
                }
                // Exponential backoff with jitter
                try {
                    long backoff = (1L << attempts) * 10 + ThreadLocalRandom.current().nextLong(5, 20);
                    Thread.sleep(backoff);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Interrupted during optimistic retry", ie);
                }
            } catch (DataIntegrityViolationException e) {
                Optional<TransferResponse> replay = support.checkIdempotency(idempotencyKey, requestHash);
                if (replay.isPresent()) {
                    return replay.get();
                }
                throw e;
            }
        }

        throw new OptimisticLockingFailureException("Exceeded maximum optimistic retries (" + MAX_RETRIES + ")");
    }
}
