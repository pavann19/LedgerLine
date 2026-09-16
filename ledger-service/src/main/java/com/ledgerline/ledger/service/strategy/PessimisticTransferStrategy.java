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
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Component
public class PessimisticTransferStrategy implements TransferExecutionStrategy {

    private final TransferCoreSupport support;
    private final AccountBalanceRepository balanceRepository;
    private final TransactionTemplate transactionTemplate;

    public PessimisticTransferStrategy(
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
        return IsolationVariant.VARIANT_1_PESSIMISTIC;
    }

    @Override
    public TransferResponse execute(String idempotencyKey, TransferRequest request, byte[] requestHash) {
        // Fast-path idempotency check
        Optional<TransferResponse> cached = support.checkIdempotency(idempotencyKey, requestHash);
        if (cached.isPresent()) {
            return cached.get();
        }

        support.validateTransferRequest(request);

        try {
            return transactionTemplate.execute(status -> {
                // Check idempotency inside transaction in case it was just committed
                Optional<TransferResponse> replay = support.checkIdempotency(idempotencyKey, requestHash);
                if (replay.isPresent()) {
                    return replay.get();
                }

                // Deterministic Lock Acquisition: Lowest UUID first to eliminate deadlocks
                UUID fromId = request.fromAccountId();
                UUID toId = request.toAccountId();
                boolean fromFirst = fromId.compareTo(toId) < 0;
                UUID firstLockId = fromFirst ? fromId : toId;
                UUID secondLockId = fromFirst ? toId : fromId;

                AccountBalance firstBalance = balanceRepository.findByIdForUpdate(firstLockId)
                    .orElseThrow(() -> new AccountNotFoundException(firstLockId));
                AccountBalance secondBalance = balanceRepository.findByIdForUpdate(secondLockId)
                    .orElseThrow(() -> new AccountNotFoundException(secondLockId));

                AccountBalance fromBalance = fromFirst ? firstBalance : secondBalance;
                AccountBalance toBalance = fromFirst ? secondBalance : firstBalance;

                // Funds check for non-overdraft accounts
                if (fromBalance.accountType() != AccountType.OVERDRAFT && fromBalance.balanceMinor() < request.amountMinor()) {
                    throw new InsufficientFundsException(String.format(
                        "Insufficient funds in account %s: available %d, required %d",
                        fromId, fromBalance.balanceMinor(), request.amountMinor()
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

                // Update cached balances
                balanceRepository.updateBalance(new AccountBalance(
                    fromId,
                    fromBalance.balanceMinor() - request.amountMinor(),
                    fromBalance.accountType(),
                    fromBalance.version() + 1
                ));

                balanceRepository.updateBalance(new AccountBalance(
                    toId,
                    toBalance.balanceMinor() + request.amountMinor(),
                    toBalance.accountType(),
                    toBalance.version() + 1
                ));

                return response;
            });
        } catch (DataIntegrityViolationException e) {
            // After transaction rollback, check if concurrent duplicate committed the same key
            Optional<TransferResponse> replay = support.checkIdempotency(idempotencyKey, requestHash);
            if (replay.isPresent()) {
                return replay.get();
            }
            throw e;
        }
    }
}
