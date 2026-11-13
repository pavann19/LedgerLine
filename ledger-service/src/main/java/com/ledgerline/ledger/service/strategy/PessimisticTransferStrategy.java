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
import com.ledgerline.ledger.service.performance.TransferPhaseMetrics;
import static com.ledgerline.ledger.service.performance.TransferPhaseMetrics.Phase.*;
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
    private final TransferPhaseMetrics metrics;

    public PessimisticTransferStrategy(
        TransferCoreSupport support,
        AccountBalanceRepository balanceRepository,
        PlatformTransactionManager transactionManager
    ) {
        this(support, balanceRepository, transactionManager,
            new TransferPhaseMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()));
    }

    @org.springframework.beans.factory.annotation.Autowired
    public PessimisticTransferStrategy(TransferCoreSupport support,
        AccountBalanceRepository balanceRepository, PlatformTransactionManager transactionManager,
        TransferPhaseMetrics metrics) {
        this.support = support;
        this.balanceRepository = balanceRepository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.metrics = metrics;
    }

    @Override
    public IsolationVariant getVariant() {
        return IsolationVariant.VARIANT_1_PESSIMISTIC;
    }

    @Override
    public TransferResponse execute(String idempotencyKey, TransferRequest request, byte[] requestHash) {
        // Resolve replays once, under the transaction-scoped key lock below.
        // A speculative miss here consumes another pooled connection per new transfer.
        // Account metadata is authoritatively checked under share locks below.
        // Avoid two speculative reads that would immediately be repeated there.
        support.validateTransferShape(request);

        try {
            return metrics.measure(TOTAL_TRANSACTION, () -> {
                long requested = System.nanoTime();
                var bodyEnded = new java.util.concurrent.atomic.AtomicLong();
                var balancesAcquired = new java.util.concurrent.atomic.AtomicLong();
                TransferResponse result = transactionTemplate.execute(status -> {
                metrics.record(TRANSACTION_START, requested);
                try {
                metrics.measure(KEY_LOCK, () -> { support.lockIdempotencyKey(idempotencyKey); return null; });
                // Check idempotency inside transaction in case it was just committed
                Optional<TransferResponse> replay = metrics.measure(REPLAY_CHECK,
                    () -> support.checkIdempotency(idempotencyKey, requestHash));
                if (replay.isPresent()) {
                    return replay.get();
                }

                metrics.measure(METADATA_LOCK, () -> { support.validateLockedTransferRequest(request); return null; });

                // Deterministic Lock Acquisition: Lowest UUID first to eliminate deadlocks
                UUID fromId = request.fromAccountId();
                UUID toId = request.toAccountId();
                boolean fromFirst = fromId.compareTo(toId) < 0;
                UUID firstLockId = fromFirst ? fromId : toId;
                UUID secondLockId = fromFirst ? toId : fromId;

                var lockedBalances = metrics.measure(BALANCE_LOCK,
                    () -> balanceRepository.findPairForUpdate(firstLockId, secondLockId));
                balancesAcquired.set(System.nanoTime());
                AccountBalance firstBalance = lockedBalances.stream().filter(b -> b.accountId().equals(firstLockId)).findFirst()
                    .orElseThrow(() -> new AccountNotFoundException(firstLockId));
                AccountBalance secondBalance = lockedBalances.stream().filter(b -> b.accountId().equals(secondLockId)).findFirst()
                    .orElseThrow(() -> new AccountNotFoundException(secondLockId));

                // The transaction-scoped idempotency lock is still held. Same-key
                // callers cannot commit after the replay check above, so another
                // database round trip here only extends the hot balance lock hold.

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

                // Check arithmetic before persistence; write journal and both caches
                // in one command to shorten the serialized balance-lock section.
                var updatedFrom = new AccountBalance(
                    fromId,
                    Math.subtractExact(fromBalance.balanceMinor(), request.amountMinor()),
                    fromBalance.accountType(),
                    Math.incrementExact(fromBalance.version())
                );
                var updatedTo = new AccountBalance(
                    toId,
                    Math.addExact(toBalance.balanceMinor(), request.amountMinor()),
                    toBalance.accountType(),
                    Math.incrementExact(toBalance.version())
                );
                return metrics.measure(PERSIST, () -> support.recordTransactionAndPostings(transactionId,
                    idempotencyKey, requestHash, request, now, java.util.List.of(updatedFrom, updatedTo)));
                } finally { bodyEnded.set(System.nanoTime()); }
                });
                metrics.record(COMMIT_CLEANUP, bodyEnded.get());
                if (balancesAcquired.get() != 0) metrics.record(BALANCE_LOCK_TO_COMPLETION, balancesAcquired.get());
                return result;
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
