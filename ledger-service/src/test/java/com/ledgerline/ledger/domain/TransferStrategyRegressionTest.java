package com.ledgerline.ledger.domain;

import com.ledgerline.ledger.dto.*;
import com.ledgerline.ledger.repository.AccountBalanceRepository;
import com.ledgerline.ledger.service.TransferCoreSupport;
import com.ledgerline.ledger.service.strategy.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import java.time.Instant;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

class TransferStrategyRegressionTest {
    @ParameterizedTest
    @EnumSource(value = IsolationVariant.class, names = {"VARIANT_1_PESSIMISTIC", "VARIANT_2_OPTIMISTIC", "VARIANT_3_SERIALIZABLE"})
    void overflowRequestsRollbackInsteadOfWrapping(IsolationVariant variant) {
        TransferCoreSupport support = mock(TransferCoreSupport.class);
        AccountBalanceRepository balances = mock(AccountBalanceRepository.class);
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        UUID from = new UUID(0, 1), to = new UUID(0, 2);
        when(support.checkIdempotency(anyString(), any())).thenReturn(Optional.empty());
        when(balances.findById(from)).thenReturn(Optional.of(new AccountBalance(from, 0, AccountType.OVERDRAFT, 0)));
        when(balances.findById(to)).thenReturn(Optional.of(new AccountBalance(to, Long.MAX_VALUE, AccountType.OVERDRAFT, 0)));
        when(balances.findByIdForUpdate(from)).thenReturn(Optional.of(new AccountBalance(from, 0, AccountType.OVERDRAFT, 0)));
        when(balances.findByIdForUpdate(to)).thenReturn(Optional.of(new AccountBalance(to, Long.MAX_VALUE, AccountType.OVERDRAFT, 0)));
        when(balances.findPairForUpdate(from,to)).thenReturn(List.of(
            new AccountBalance(from,0,AccountType.OVERDRAFT,0),new AccountBalance(to,Long.MAX_VALUE,AccountType.OVERDRAFT,0)));
        when(balances.updateBalanceOptimistic(any(), anyLong(), anyLong())).thenReturn(1);
        TransferExecutionStrategy strategy = switch (variant) {
            case VARIANT_1_PESSIMISTIC -> new PessimisticTransferStrategy(support, balances, manager);
            case VARIANT_2_OPTIMISTIC -> new OptimisticTransferStrategy(support, balances, manager);
            case VARIANT_3_SERIALIZABLE -> new SerializableTransferStrategy(support, balances, manager);
            default -> throw new AssertionError();
        };
        assertThrows(ArithmeticException.class, () -> strategy.execute("key", new TransferRequest(from, to, 1, "USD"), new byte[32]));
        verify(manager).rollback(any());
        verify(manager, never()).commit(any());
    }

    @Test void duplicateCommittedWhileWaitingForLocksReplaysBeforeFundsCheck() {
        TransferCoreSupport support = mock(TransferCoreSupport.class);
        AccountBalanceRepository balances = mock(AccountBalanceRepository.class);
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        UUID from = new UUID(0, 1), to = new UUID(0, 2);
        TransferResponse original = new TransferResponse(UUID.randomUUID(), "key", TransactionStatus.POSTED,
            Instant.now(), List.of(), true);
        when(support.checkIdempotency(anyString(), any())).thenReturn(Optional.empty(), Optional.empty(), Optional.of(original));
        when(balances.findByIdForUpdate(from)).thenReturn(Optional.of(new AccountBalance(from, 0, AccountType.CUSTOMER, 1)));
        when(balances.findByIdForUpdate(to)).thenReturn(Optional.of(new AccountBalance(to, 1, AccountType.CUSTOMER, 1)));
        when(balances.findPairForUpdate(from,to)).thenReturn(List.of(
            new AccountBalance(from,0,AccountType.CUSTOMER,1),new AccountBalance(to,1,AccountType.CUSTOMER,1)));
        assertSame(original, new PessimisticTransferStrategy(support, balances, manager)
            .execute("key", new TransferRequest(from, to, 1, "USD"), new byte[32]));
        verify(support, never()).recordTransactionAndPostings(any(), anyString(), any(), any(), any());
        verify(balances, never()).updateBalance(any());
        verify(balances, never()).updatePair(any(),any());
    }
}
