package com.ledgerline.ledger.concurrency;

import com.ledgerline.ledger.domain.AccountType;
import com.ledgerline.ledger.dto.*;
import com.ledgerline.ledger.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class DatabaseFailureRecoveryTest extends BaseIntegrationTest {
    @Autowired AccountService accounts;
    @Autowired TransferService transfers;
    @Autowired JdbcClient jdbc;
    @Autowired PlatformTransactionManager manager;
    UUID account() { return accounts.createAccount(new CreateAccountRequest("USD",AccountType.OVERDRAFT)).id(); }

    @Test void statementTimeoutAfterWritesRollsBackThenSameKeyCanSucceed() {
        UUID from=account(), to=account(); String key="timeout-"+UUID.randomUUID();
        var request=new TransferRequest(from,to,7,"USD");
        assertThrows(org.springframework.dao.DataAccessException.class,()->new TransactionTemplate(manager).execute(status->{
            transfers.transfer(key,request,null);
            jdbc.sql("SET LOCAL statement_timeout='100ms'").update();
            jdbc.sql("SELECT pg_sleep(1)").query((rs,n)->0).single();
            return null;
        }));
        assertEquals(0,accounts.getAccount(from).balanceMinor());
        assertEquals(0L,jdbc.sql("SELECT count(*) FROM transactions WHERE idempotency_key=?").param(key).query(Long.class).single());
        assertEquals(0L,jdbc.sql("SELECT count(*) FROM postings WHERE account_id IN (?,?)").params(from,to).query(Long.class).single());
        var first=transfers.transfer(key,request,null);
        // Simulated lost client reply: discard the first result, retry the key.
        var replay=transfers.transfer(key,request,null);
        assertEquals(first.transactionId(),replay.transactionId()); assertTrue(replay.idempotentReplay());
        assertEquals(-7,accounts.getAccount(from).balanceMinor());
        assertEquals(7,accounts.getAccount(to).balanceMinor());
        assertEquals(1L,jdbc.sql("SELECT count(*) FROM outbox WHERE aggregate_id=?").param(first.transactionId()).query(Long.class).single());
    }
    @Test void realPostgresDeadlockVictimRollsBackEveryFinancialWrite() throws Exception {
        UUID a=account(), b=account(), c=account(), d=account();
        var barrier=new CyclicBarrier(2);
        try(var pool=Executors.newFixedThreadPool(2)) {
            Future<Boolean> one=pool.submit(()->deadlockTransfer(a,b,c,barrier));
            Future<Boolean> two=pool.submit(()->deadlockTransfer(c,d,a,barrier));
            assertNotEquals(one.get(20,TimeUnit.SECONDS),two.get(20,TimeUnit.SECONDS));
        }
        assertEquals(0L,jdbc.sql("SELECT sum(balance_minor) FROM account_balances WHERE account_id IN (?,?,?,?)").params(a,b,c,d).query(Long.class).single());
        assertEquals(2L,jdbc.sql("SELECT count(*) FROM postings WHERE account_id IN (?,?,?,?)").params(a,b,c,d).query(Long.class).single());
        assertEquals(1L,jdbc.sql("SELECT count(*) FROM outbox WHERE payload->>'fromAccountId' IN (?,?)").params(a.toString(),c.toString()).query(Long.class).single());
    }
    boolean deadlockTransfer(UUID from,UUID to,UUID other,CyclicBarrier barrier) {
        try {
            new TransactionTemplate(manager).execute(status->{
                jdbc.sql("SET LOCAL deadlock_timeout='100ms'").update();
                transfers.transfer("deadlock-"+UUID.randomUUID(),new TransferRequest(from,to,1,"USD"),null);
                try { barrier.await(5,TimeUnit.SECONDS); } catch(Exception e) { throw new IllegalStateException(e); }
                jdbc.sql("SELECT balance_minor FROM account_balances WHERE account_id=? FOR UPDATE").param(other).query(Long.class).single();
                return null;
            });
            return true;
        } catch(org.springframework.dao.DataAccessException e) {
            Throwable root=e; while(root.getCause()!=null) root=root.getCause();
            assertInstanceOf(java.sql.SQLException.class,root);
            assertEquals("40P01",((java.sql.SQLException)root).getSQLState());
            return false;
        }
    }
}
