package com.ledgerline.ledger.security;

import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.*;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Explicitly selects local HS256 or an external OAuth2 RS256 resource server. */
final class JwtDecoderFactory {
    private JwtDecoderFactory() {}

    static JwtDecoder create(String mode, String secret, String issuer, String jwks, String audience) {
        NimbusJwtDecoder decoder;
        if ("local-jwt".equals(mode)) {
            if (!issuer.isBlank() || !jwks.isBlank() || !audience.isBlank()) {
                throw new IllegalArgumentException("Set security.mode=oauth2 to use provider settings");
            }
            if (secret.getBytes(StandardCharsets.UTF_8).length < 32) {
                throw new IllegalArgumentException("JWT secret must contain at least 32 bytes");
            }
            decoder = NimbusJwtDecoder.withSecretKey(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256")).build();
            decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefault(), subjectValidator(), expiryValidator()));
        } else if ("oauth2".equals(mode)) {
            requireProviderUri(issuer);
            if (audience.isBlank()) throw new IllegalArgumentException("OAuth2 audience is required");
            if (jwks.isBlank()) {
                decoder = NimbusJwtDecoder.withIssuerLocation(issuer).build();
            } else {
                requireProviderUri(jwks);
                decoder = NimbusJwtDecoder.withJwkSetUri(jwks).build();
            }
            // Nimbus's default asymmetric algorithm is RS256. Never fall back to local HMAC.
            decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuer), subjectValidator(), expiryValidator(),
                new JwtClaimValidator<List<String>>("aud", values -> values != null && values.contains(audience))));
        } else {
            throw new IllegalArgumentException("security.mode must be local-jwt or oauth2");
        }
        return decoder;
    }

    static void requireProviderUri(String value) {
        URI uri = URI.create(value);
        boolean loopback = List.of("localhost", "127.0.0.1", "[::1]", "::1").contains(uri.getHost() == null ? "" : uri.getHost());
        if (uri.getHost() == null || uri.getRawUserInfo() != null || uri.getFragment() != null
            || !("https".equals(uri.getScheme()) || ("http".equals(uri.getScheme()) && loopback))) {
            throw new IllegalArgumentException("Provider URLs must use HTTPS (loopback HTTP is allowed for local tests)");
        }
    }

    private static JwtClaimValidator<String> subjectValidator() {
        return new JwtClaimValidator<>("sub", sub -> sub != null && !sub.isBlank() && sub.indexOf('\u001F') < 0);
    }
    private static JwtClaimValidator<java.time.Instant> expiryValidator() {
        return new JwtClaimValidator<>("exp", exp -> exp != null);
    }
}
