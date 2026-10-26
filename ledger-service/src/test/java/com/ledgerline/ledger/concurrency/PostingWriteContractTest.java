package com.ledgerline.ledger.concurrency;

import com.ledgerline.ledger.domain.AccountType;
import com.ledgerline.ledger.domain.IsolationVariant;
import com.ledgerline.ledger.domain.Posting;
import com.ledgerline.ledger.dto.CreateAccountRequest;
import com.ledgerline.ledger.dto.PostingDto;
import com.ledgerline.ledger.dto.TransferRequest;
import com.ledgerline.ledger.repository.PostingRepository;
import com.ledgerline.ledger.service.AccountService;
import com.ledgerline.ledger.service.TransferService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PostingWriteContractTest extends BaseIntegrationTest {
    @Autowired AccountService accounts;
    @Autowired TransferService transfers;
    @Autowired PostingRepository postings;
    @Autowired JdbcClient jdbc;
    @Autowired PlatformTransactionManager manager;
    @Autowired com.ledgerline.ledger.repository.TransferWriteRepository journal;

    @Test
    void failedAuditRollsBackJournalHeaderAndOutboxTogether() {
        UUID from = accounts.createAccount(new CreateAccountRequest("USD",AccountType.OVERDRAFT)).id();
        UUID to = accounts.createAccount(new CreateAccountRequest("USD",AccountType.CUSTOMER)).id();
        UUID id = UUID.randomUUID();
        assertThrows(org.springframework.dao.DataAccessException.class, () ->
            new TransactionTemplate(manager).execute(status -> journal.insert(id,"atomic-"+id,new byte[32],
                from,to,1,"USD",Instant.now(),"{}","SYSTEM","invalid-json")));
        assertEquals(0L,jdbc.sql("SELECT count(*) FROM transactions WHERE id=?").param(id).query(Long.class).single());
        assertTrue(postings.findByTransactionId(id).isEmpty());
        assertEquals(0L,jdbc.sql("SELECT count(*) FROM outbox WHERE aggregate_id=?").param(id).query(Long.class).single());
        assertEquals(0L,jdbc.sql("SELECT count(*) FROM audit_log WHERE entity_id=?").param(id.toString()).query(Long.class).single());
    }

    @ParameterizedTest
    @EnumSource(value = IsolationVariant.class, names = {
        "VARIANT_1_PESSIMISTIC", "VARIANT_2_OPTIMISTIC", "VARIANT_3_SERIALIZABLE"})
    void freshAndReplayedPostingsMatchStoredValuesAndOrder(IsolationVariant variant) {
        UUID from = accounts.createAccount(new CreateAccountRequest("USD", AccountType.OVERDRAFT)).id();
        UUID to = accounts.createAccount(new CreateAccountRequest("USD", AccountType.CUSTOMER)).id();
        long amount = 9_007_199_254_740_993L;
        var request = new TransferRequest(from, to, amount, "USD");
        String key = "posting-contract-" + UUID.randomUUID();
        var initial = transfers.transfer(key, request, variant);
        var replay = transfers.transfer(key, request, variant);
        var stored = postings.findByTransactionId(initial.transactionId()).stream()
            .map(p -> new PostingDto(p.id(), p.accountId(), p.amountMinor(), p.currency(), p.createdAt()))
            .toList();

        assertEquals(2, stored.size());
        assertEquals(stored, initial.postings(), "Fresh response must use stored IDs and timestamp precision");
        assertEquals(stored, replay.postings());
        assertTrue(replay.idempotentReplay());
        assertTrue(stored.get(0).id() < stored.get(1).id());
        assertEquals(from, stored.get(0).accountId());
        assertEquals(-amount, stored.get(0).amountMinor());
        assertEquals(to, stored.get(1).accountId());
        assertEquals(amount, stored.get(1).amountMinor());
        assertEquals(0, stored.get(0).createdAt().getNano() % 1_000);
        assertEquals(-amount, accounts.getAccount(from).balanceMinor());
        assertEquals(amount, accounts.getAccount(to).balanceMinor());
        assertEquals(1L,jdbc.sql("SELECT count(*) FROM outbox WHERE aggregate_id=?")
            .param(initial.transactionId()).query(Long.class).single());
        assertEquals(1L,jdbc.sql("SELECT count(*) FROM audit_log WHERE entity_id=? AND action='TRANSFER_POSTED'")
            .param(initial.transactionId().toString()).query(Long.class).single());
    }

    @Test
    void invalidCreditRollsBackTheWholePairAndTransactionHeader() {
        UUID from = accounts.createAccount(new CreateAccountRequest("USD", AccountType.OVERDRAFT)).id();
        UUID to = accounts.createAccount(new CreateAccountRequest("EUR", AccountType.CUSTOMER)).id();
        UUID transactionId = UUID.randomUUID();
        Instant now = Instant.parse("2026-10-26T00:00:00.123456789Z");

        assertThrows(org.springframework.dao.DataAccessException.class, () ->
            new TransactionTemplate(manager).execute(status -> {
                jdbc.sql("INSERT INTO transactions (id,idempotency_key,request_hash,status) VALUES (?,?,?,'POSTED')")
                    .params(transactionId, "invalid-pair-" + transactionId, new byte[32]).update();
                return postings.insertPairReturning(
                    new Posting(null, transactionId, from, -1L, "USD", now),
                    new Posting(null, transactionId, to, 1L, "USD", now));
            }));

        assertEquals(0, jdbc.sql("SELECT count(*) FROM transactions WHERE id = ?")
            .param(transactionId).query(Integer.class).single());
        assertTrue(postings.findByTransactionId(transactionId).isEmpty());
        assertEquals(0L, accounts.getAccount(from).balanceMinor());
        assertEquals(0L, accounts.getAccount(to).balanceMinor());
    }
}
