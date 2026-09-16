package com.ledgerline.ledger.domain;

import com.ledgerline.ledger.dto.TransferRequest;
import com.ledgerline.ledger.service.RequestHasher;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class RequestHasherTest {

    private final RequestHasher hasher = new RequestHasher();

    @Test
    void shouldProduceIdenticalHashForIdenticalRequests() {
        UUID from = UUID.randomUUID();
        UUID to = UUID.randomUUID();

        TransferRequest req1 = new TransferRequest(from, to, 5000L, "USD");
        TransferRequest req2 = new TransferRequest(from, to, 5000L, "USD");

        byte[] hash1 = hasher.computeHash(req1);
        byte[] hash2 = hasher.computeHash(req2);

        assertArrayEquals(hash1, hash2);
    }

    @Test
    void shouldProduceDifferentHashWhenAnyFieldChanges() {
        UUID from = UUID.randomUUID();
        UUID to = UUID.randomUUID();

        TransferRequest base = new TransferRequest(from, to, 5000L, "USD");
        byte[] baseHash = hasher.computeHash(base);

        // Different amount
        assertFalse(Arrays.equals(baseHash, hasher.computeHash(new TransferRequest(from, to, 5001L, "USD"))));

        // Different currency
        assertFalse(Arrays.equals(baseHash, hasher.computeHash(new TransferRequest(from, to, 5000L, "EUR"))));

        // Swapped accounts
        assertFalse(Arrays.equals(baseHash, hasher.computeHash(new TransferRequest(to, from, 5000L, "USD"))));
    }
}
