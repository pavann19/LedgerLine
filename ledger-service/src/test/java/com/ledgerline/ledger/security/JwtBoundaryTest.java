package com.ledgerline.ledger.security;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.*;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwtException;
import java.time.Instant;
import java.util.Date;
import static org.junit.jupiter.api.Assertions.*;
class JwtBoundaryTest {
    private static final String SECRET = "test-secret-with-at-least-32-bytes-long";
    private String token(String subject, Instant expiry) throws Exception {
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder().subject(subject).claim("roles", java.util.List.of("CUSTOMER"));
        if (expiry != null) claims.expirationTime(Date.from(expiry));
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims.build());
        jwt.sign(new MACSigner(SECRET));
        return jwt.serialize();
    }
    @Test void requiresSubjectExpiryAndStrongSecret() throws Exception {
        var decoder = new SecurityConfig().jwtDecoder(SECRET);
        assertEquals("customer", decoder.decode(token("customer", Instant.now().plusSeconds(300))).getSubject());
        for (String subject : java.util.Arrays.asList(null, "", " ", "a\u001Fb")) {
            String raw = token(subject, Instant.now().plusSeconds(300));
            assertThrows(JwtException.class, () -> decoder.decode(raw));
        }
        String noExpiry = token("customer", null);
        assertThrows(JwtException.class, () -> decoder.decode(noExpiry));
        String expired = token("customer", Instant.now().minusSeconds(300));
        assertThrows(JwtException.class, () -> decoder.decode(expired));
        assertThrows(IllegalArgumentException.class, () -> new SecurityConfig().jwtDecoder("short"));
    }
}
