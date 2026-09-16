package com.ledgerline.ledger.service;

import com.ledgerline.ledger.dto.TransferRequest;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

@Component
public class RequestHasher {

    public byte[] computeHash(TransferRequest request) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            // Deterministic byte packing
            ByteBuffer buffer = ByteBuffer.allocate(16 + 16 + 8);
            buffer.putLong(request.fromAccountId().getMostSignificantBits());
            buffer.putLong(request.fromAccountId().getLeastSignificantBits());
            buffer.putLong(request.toAccountId().getMostSignificantBits());
            buffer.putLong(request.toAccountId().getLeastSignificantBits());
            buffer.putLong(request.amountMinor());
            digest.update(buffer.array());
            digest.update(request.currency().trim().toUpperCase().getBytes(StandardCharsets.UTF_8));
            return digest.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }
}
