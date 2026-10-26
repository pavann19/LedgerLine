package com.ledgerline.projection.security;

import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/** Per-process token buckets. All consumption and capacity checks are atomic. */
final class PrincipalRateLimiter {
    record Decision(boolean allowed, int remaining, long retryAfterSeconds) {}
    private static final class Bucket {
        double tokens;
        long refilledAt;
        long lastSeen;
        Bucket(int capacity, long now) { tokens = capacity; refilledAt = now; lastSeen = now; }
    }
    private final Map<String, Bucket> buckets = new HashMap<>();
    private final double requestsPerSecond;
    private final int burst;
    private final int maxPrincipals;
    private final long idleNanos;
    private final LongSupplier clock;

    PrincipalRateLimiter(int requestsPerSecond, int burst, int maxPrincipals, long idleSeconds, LongSupplier clock) {
        if (requestsPerSecond < 1 || requestsPerSecond > 1_000_000 || burst < 1 || burst > 1_000_000
            || maxPrincipals < 1 || maxPrincipals > 1_000_000 || idleSeconds < 1 || idleSeconds > 86_400
            || idleSeconds < (double) burst / requestsPerSecond) {
            throw new IllegalArgumentException("Invalid rate-limit configuration; idle expiry must allow a full refill");
        }
        this.requestsPerSecond = requestsPerSecond;
        this.burst = burst;
        this.maxPrincipals = maxPrincipals;
        this.idleNanos = idleSeconds * 1_000_000_000L;
        this.clock = clock;
    }

    synchronized Decision acquire(String principal) {
        long now = clock.getAsLong();
        Bucket bucket = buckets.get(principal);
        if (bucket == null) {
            if (buckets.size() >= maxPrincipals) {
                // Never evict active principals: eviction would reset an exhausted quota.
                buckets.values().removeIf(value -> now - value.lastSeen >= idleNanos);
            }
            if (buckets.size() >= maxPrincipals) {
                long nextExpiry = buckets.values().stream().mapToLong(value -> idleNanos - (now - value.lastSeen)).min().orElse(idleNanos);
                return new Decision(false, 0, Math.max(1, (long) Math.ceil(nextExpiry / 1_000_000_000.0)));
            }
            bucket = new Bucket(burst, now);
            buckets.put(principal, bucket);
        }
        bucket.tokens = Math.min(burst, bucket.tokens + Math.max(0, now - bucket.refilledAt) / 1_000_000_000.0 * requestsPerSecond);
        bucket.refilledAt = now;
        bucket.lastSeen = now;
        if (bucket.tokens < 1) {
            return new Decision(false, 0, Math.max(1, (long) Math.ceil((1 - bucket.tokens) / requestsPerSecond)));
        }
        bucket.tokens -= 1;
        return new Decision(true, (int) bucket.tokens, 0);
    }
}
