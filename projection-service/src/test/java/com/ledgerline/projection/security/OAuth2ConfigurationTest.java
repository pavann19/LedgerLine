package com.ledgerline.projection.security;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OAuth2ConfigurationTest {
    @Test void refusesIncompleteOrAmbiguousProviderConfiguration() {
        assertThrows(IllegalArgumentException.class, () -> JwtDecoderFactory.create("oauth2", "", "", "", "api"));
        assertThrows(IllegalArgumentException.class, () -> JwtDecoderFactory.create("oauth2", "", "https://identity.example.invalid", "", ""));
        assertThrows(IllegalArgumentException.class, () -> JwtDecoderFactory.create("local-jwt", "a".repeat(32), "https://identity.example.invalid", "", "api"));
        assertThrows(IllegalArgumentException.class, () -> JwtDecoderFactory.create("typo", "a".repeat(32), "", "", ""));
    }
    @Test void acceptsOnlySecureOrLoopbackProviderUrls() {
        for (String uri : java.util.List.of("", "http://identity.example.invalid", "https://user:password@identity.example.invalid", "https://identity.example.invalid/#fragment")) {
            assertThrows(IllegalArgumentException.class, () -> JwtDecoderFactory.requireProviderUri(uri));
        }
        assertDoesNotThrow(() -> JwtDecoderFactory.requireProviderUri("https://identity.example.invalid/jwks"));
        assertDoesNotThrow(() -> JwtDecoderFactory.requireProviderUri("http://127.0.0.1:8089/jwks"));
    }
}
