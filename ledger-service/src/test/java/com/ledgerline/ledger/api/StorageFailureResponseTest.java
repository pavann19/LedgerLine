package com.ledgerline.ledger.api;

import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionTimedOutException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class StorageFailureResponseTest {
    @Test void storageFailuresPreserveUncertaintyWithoutLeakingDatabaseDetails() {
        var handler = new GlobalExceptionHandler();
        var request = new MockHttpServletRequest("POST", "/api/v1/transfers");
        String sensitive = "jdbc:postgresql://private-host/password=secret";
        for (Exception failure : List.of(new CannotAcquireLockException(sensitive),
            new DataAccessResourceFailureException(sensitive),
            new CannotCreateTransactionException(sensitive),
            new TransactionTimedOutException(sensitive))) {
            var response = handler.unavailable(failure, request);
            assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
            var body = response.getBody();
            assertNotNull(body);
            assertTrue(body.getDetail().contains("outcome may be unknown"));
            assertTrue(body.getDetail().contains("same principal, Idempotency-Key and request body"));
            assertFalse(body.toString().contains(sensitive));
        }
    }
}
