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
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Deliberately broken transfer implementation:
 * READ COMMITTED, read-then-write without locking or version checks.
 * Under concurrent load, demonstrates the classic lost-update anomaly.
 */
@Component
public class BrokenTransferStrategy implements TransferExecutionStrategy {

    private final TransferCoreSupport support;
    private final AccountBalanceRepository balanceRepository;

    public BrokenTransferStrategy(TransferCoreSupport support, AccountBalanceRepository balanceRepository) {
        this.support = support;
        this.balanceRepository = balanceRepository;
    }

    @Override
    public IsolationVariant getVariant() {
        return IsolationVariant.VARIANT_0_BROKEN;
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public TransferResponse execute(String idempotencyKey, TransferRequest request, byte[] requestHash) {
        Optional<TransferResponse> cached = support.checkIdempotency(idempotencyKey, requestHash);
        if (cached.isPresent()) {
            return cached.get();
        }

        support.validateTransferRequest(request);

        // Read balances WITHOUT locks (race condition window)
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

        // Deliberate tiny micro-delay to widen race window in concurrency tests
        try {
            Thread.sleep(1);
        } catch (InterruptedException ignored) {}

        UUID transactionId = UUID.randomUUID();
        Instant now = Instant.now();

        TransferResponse response = support.recordTransactionAndPostings(
            transactionId,
            idempotencyKey,
            requestHash,
            request,
            now
        );

        // Blind overwrite without lock or version check: LOST UPDATE ANOMALY!
        balanceRepository.updateBalanceBroken(
            request.fromAccountId(),
            fromBalance.balanceMinor() - request.amountMinor()
        );
        balanceRepository.updateBalanceBroken(
            request.toAccountId(),
            toBalance.balanceMinor() + request.amountMinor()
        );

        return response;
    }
}
