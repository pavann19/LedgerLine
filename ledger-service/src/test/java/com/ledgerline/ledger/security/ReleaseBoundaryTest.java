package com.ledgerline.ledger.security;

import com.ledgerline.ledger.api.TransferController;
import com.ledgerline.ledger.domain.*;
import com.ledgerline.ledger.dto.*;
import com.ledgerline.ledger.repository.*;
import com.ledgerline.ledger.service.*;
import com.ledgerline.ledger.service.strategy.TransferExecutionStrategy;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ReleaseBoundaryTest {
    @Test
    void customerCannotCreateOverdraftOrInternalAccount() {
        AccountRepository repository = mock(AccountRepository.class);
        AccountService service = new AccountService(repository, mock(AccountBalanceRepository.class),
            mock(PostingRepository.class), mock(AuditLogRepository.class));
        for (AccountType type : List.of(AccountType.OVERDRAFT, AccountType.INTERNAL)) {
            assertThrows(AccessDeniedException.class,
                () -> service.createAccount(new CreateAccountRequest("USD", type), "customer", false));
        }
        verifyNoInteractions(repository);
    }

    @Test
    void publicControllerRejectsBrokenIsolationOverride() {
        TransferService service = mock(TransferService.class);
        when(service.transfer(anyString(), any(), any(), any(), anyBoolean())).thenReturn(
            new TransferResponse(UUID.randomUUID(), "key", TransactionStatus.POSTED, Instant.now(), List.of(), false));
        TransferController controller = new TransferController(service, mock(CurrentPrincipal.class));
        for (IsolationVariant variant : List.of(IsolationVariant.VARIANT_0_BROKEN)) {
            assertThrows(IllegalArgumentException.class, () -> controller.transfer("key", variant,
                new TransferRequest(UUID.randomUUID(), UUID.randomUUID(), 1, "USD")));
        }
        verifyNoInteractions(service);
    }

    @Test
    void authenticatedSystemSubjectStillGetsItsOwnIdempotencyNamespace() {
        UUID from = UUID.randomUUID(), to = UUID.randomUUID();
        AccountRepository accounts = mock(AccountRepository.class);
        when(accounts.findById(from)).thenReturn(Optional.of(new Account(from, "USD", AccountType.CUSTOMER,
            AccountStatus.ACTIVE, Instant.now(), 0, "SYSTEM")));
        TransferExecutionStrategy strategy = mock(TransferExecutionStrategy.class);
        when(strategy.getVariant()).thenReturn(IsolationVariant.VARIANT_1_PESSIMISTIC);
        when(strategy.execute(anyString(), any(), any())).thenReturn(new TransferResponse(UUID.randomUUID(),
            "key", TransactionStatus.POSTED, Instant.now(), List.of(), false));
        TransferService service = new TransferService(List.of(strategy), new RequestHasher(),
            new SimpleMeterRegistry(), accounts, "VARIANT_1_PESSIMISTIC");
        TransferRequest request = new TransferRequest(from, to, 1, "USD");
        service.transfer("key", request, null, "SYSTEM", false);
        verify(strategy).execute(eq("SYSTEM\u001Fkey"), eq(request), any());
        for (String key : List.of("", " ", "x\u001Fy", "x".repeat(129))) {
            assertThrows(IllegalArgumentException.class, () -> service.transfer(key, request, null, "SYSTEM", false));
        }
        assertThrows(IllegalArgumentException.class, () -> service.transfer("key", request,
            IsolationVariant.VARIANT_0_BROKEN, "SYSTEM", true));
        assertThrows(IllegalArgumentException.class, () -> new TransferService(List.of(strategy), new RequestHasher(),
            new SimpleMeterRegistry(), accounts, "VARIANT_0_BROKEN"));
    }
}
