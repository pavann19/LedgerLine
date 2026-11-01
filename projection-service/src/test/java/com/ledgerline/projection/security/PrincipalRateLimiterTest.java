package com.ledgerline.projection.security;

import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;
import static org.junit.jupiter.api.Assertions.*;

class PrincipalRateLimiterTest {
    @Test void refillsContinuouslyAndSeparatesPrincipals() {
        var now = new AtomicLong();
        var limiter = new PrincipalRateLimiter(2, 2, 10, 5, now::get);
        assertEquals(1, limiter.acquire("alice").remaining());
        assertTrue(limiter.acquire("alice").allowed());
        assertFalse(limiter.acquire("alice").allowed());
        assertEquals(1, limiter.acquire("alice").retryAfterSeconds());
        assertTrue(limiter.acquire("bob").allowed());
        now.set(499_000_000L);
        assertFalse(limiter.acquire("alice").allowed());
        now.set(500_000_000L);
        assertTrue(limiter.acquire("alice").allowed());
        assertFalse(limiter.acquire("alice").allowed());
        now.set(100_000_000_000L);
        assertEquals(1, limiter.acquire("alice").remaining(), "Refill must be capped at burst");
    }
    @Test void boundsMemoryWithoutEvictingAnExhaustedIdentity() {
        var now = new AtomicLong();
        var limiter = new PrincipalRateLimiter(1, 1, 1, 2, now::get);
        assertTrue(limiter.acquire("alice").allowed());
        assertFalse(limiter.acquire("bob").allowed());
        assertFalse(limiter.acquire("alice").allowed(), "New identities cannot evict and reset active quotas");
        now.set(2_000_000_000L);
        assertTrue(limiter.acquire("bob").allowed());
        assertFalse(limiter.acquire("alice").allowed());
    }
    @Test void concurrentRequestsCannotOverspendABucket() throws Exception {
        var limiter = new PrincipalRateLimiter(10, 20, 10, 3, () -> 0L);
        try (var pool = Executors.newFixedThreadPool(8)) {
            var futures = pool.invokeAll(IntStream.range(0, 100).<java.util.concurrent.Callable<Boolean>>mapToObj(i -> () -> limiter.acquire("same-user").allowed()).toList());
            int accepted = 0;
            for (var f : futures) if (f.get()) accepted++;
            assertEquals(20, accepted);
        }
    }
    @Test void rejectsUnsafeConfiguration() {
        assertThrows(IllegalArgumentException.class, () -> new PrincipalRateLimiter(0, 1, 1, 1, () -> 0));
        assertThrows(IllegalArgumentException.class, () -> new PrincipalRateLimiter(1, 10, 1, 1, () -> 0));
        assertThrows(IllegalArgumentException.class, () -> new PrincipalRateLimiter(1, 1, 0, 1, () -> 0));
    }
}
