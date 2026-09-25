package com.ledgerline.ledger.security;

import com.ledgerline.ledger.domain.*;
import com.ledgerline.ledger.dto.*;
import com.ledgerline.ledger.repository.AccountRepository;
import com.ledgerline.ledger.service.RequestHasher;
import com.ledgerline.ledger.service.TransferService;
import com.ledgerline.ledger.service.strategy.TransferExecutionStrategy;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PrincipalScopeTest {
    @Test
    void scopesIdempotencyKeysByPrincipalAndPreservesPublicKey() {
        UUID fromId=UUID.randomUUID(), toId=UUID.randomUUID();
        AccountRepository accounts=mock(AccountRepository.class);
        when(accounts.findById(fromId)).thenReturn(Optional.of(account(fromId,"customer-a")));
        TransferExecutionStrategy strategy=mock(TransferExecutionStrategy.class);
        when(strategy.getVariant()).thenReturn(IsolationVariant.VARIANT_1_PESSIMISTIC);
        when(strategy.execute(anyString(),any(),any())).thenAnswer(inv -> response(inv.getArgument(0)));
        TransferService service=new TransferService(List.of(strategy),new RequestHasher(),new SimpleMeterRegistry(),accounts,"VARIANT_1_PESSIMISTIC");
        TransferRequest request=new TransferRequest(fromId,toId,100,"USD");

        assertEquals("same-key",service.transfer("same-key",request,null,"customer-a",false).idempotencyKey());
        service.transfer("same-key",request,null,"operator-b",true);

        verify(strategy).execute(eq("customer-a\u001Fsame-key"),eq(request),any());
        verify(strategy).execute(eq("operator-b\u001Fsame-key"),eq(request),any());
    }

    @Test
    void customerCannotDebitAnotherCustomersAccount() {
        UUID fromId=UUID.randomUUID(),toId=UUID.randomUUID(); AccountRepository accounts=mock(AccountRepository.class);
        when(accounts.findById(fromId)).thenReturn(Optional.of(account(fromId,"owner")));
        TransferExecutionStrategy strategy=mock(TransferExecutionStrategy.class); when(strategy.getVariant()).thenReturn(IsolationVariant.VARIANT_1_PESSIMISTIC);
        TransferService service=new TransferService(List.of(strategy),new RequestHasher(),new SimpleMeterRegistry(),accounts,"VARIANT_1_PESSIMISTIC");
        assertThrows(AccessDeniedException.class,()->service.transfer("key",new TransferRequest(fromId,toId,1,"USD"),null,"intruder",false));
        verify(strategy,never()).execute(anyString(),any(),any());
    }

    private Account account(UUID id,String owner){return new Account(id,"USD",AccountType.CUSTOMER,AccountStatus.ACTIVE,Instant.now(),0,owner);}
    private TransferResponse response(String key){return new TransferResponse(UUID.randomUUID(),key,TransactionStatus.POSTED,Instant.now(),List.of(),false);}
}
