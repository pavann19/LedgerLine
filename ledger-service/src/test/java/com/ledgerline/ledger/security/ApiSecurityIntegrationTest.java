package com.ledgerline.ledger.security;

import com.ledgerline.ledger.concurrency.BaseIntegrationTest;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "ledger.security.mode=oauth2", "ledger.security.rate-limit.requests-per-second=1",
    "ledger.security.rate-limit.burst=2", "ledger.security.rate-limit.max-principals=100",
    "ledger.security.rate-limit.idle-seconds=5", "spring.kafka.listener.auto-startup=false"
})
class ApiSecurityIntegrationTest extends BaseIntegrationTest {
    private static final UUID ACCOUNT = UUID.randomUUID();
    private static final AtomicReference<RSAKey> KEY = new AtomicReference<>(newKey());
    private static final HttpServer PROVIDER = startProvider();
    private static final String ISSUER = "http://127.0.0.1:" + PROVIDER.getAddress().getPort();
    private static final String AUDIENCE = "ledgerline-test-api";
    private final HttpClient http = HttpClient.newHttpClient();
    private final JsonMapper mapper = new JsonMapper();
    @LocalServerPort int port;

    private static RSAKey newKey() {
        try { return new RSAKeyGenerator(2048).keyID(UUID.randomUUID().toString()).generate(); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    private static HttpServer startProvider() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/jwks", exchange -> {
                byte[] body = new JWKSet(KEY.get().toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (var out = exchange.getResponseBody()) { out.write(body); }
            });
            server.start();
            return server;
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
    @AfterAll static void stopProvider() { PROVIDER.stop(0); }
    @DynamicPropertySource static void providerProperties(DynamicPropertyRegistry registry) {
        registry.add("ledger.security.issuer-uri", () -> ISSUER);
        registry.add("ledger.security.jwk-set-uri", () -> ISSUER + "/jwks");
        registry.add("ledger.security.audience", () -> AUDIENCE);
        registry.add("ledger.security.oauth2.authorization-url", () -> ISSUER + "/authorize");
        registry.add("ledger.security.oauth2.token-url", () -> ISSUER + "/token");
    }
    private JWTClaimsSet.Builder claims(String subject) {
        return new JWTClaimsSet.Builder().subject(subject).issuer(ISSUER).audience(AUDIENCE)
            .expirationTime(Date.from(Instant.now().plusSeconds(300))).claim("roles", List.of("OPERATOR"))
            .claim("accounts", List.of(ACCOUNT.toString())).jwtID(UUID.randomUUID().toString());
    }
    private String sign(JWTClaimsSet.Builder claims) throws Exception { return sign(claims, KEY.get()); }
    private String sign(JWTClaimsSet.Builder claims, RSAKey key) throws Exception {
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims.build());
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }
    private String endpoint() { return "/api/v1/accounts/" + ACCOUNT; }
    private HttpResponse<String> request(String path, String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
        if (token != null) request.header("Authorization", "Bearer " + token);
        return http.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test void validatesIssuerAudienceExpirySubjectAndSignatureOverHttp() throws Exception {
        for (var invalid : List.of(claims("claims").issuer("https://wrong.example.invalid"),
            claims("claims").audience("wrong-api"), claims("claims").audience(Collections.emptyList()),
            claims("claims").expirationTime(null), claims("claims").expirationTime(Date.from(Instant.now().minusSeconds(300))),
            claims(" "), claims("bad\u001Fsubject"))) {
            var response = request(endpoint(), sign(invalid));
            assertEquals(401, response.statusCode());
            assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("application/problem+json"));
            assertEquals("Bearer", response.headers().firstValue("WWW-Authenticate").orElseThrow());
        }
        assertEquals(401, request(endpoint(), sign(claims("bad-signature"), newKey())).statusCode());
        assertEquals(404, request(endpoint(), sign(claims("accepted"))).statusCode());
    }
    @Test void refusesSharedSecretTokensInProviderMode() throws Exception {
        byte[] randomSecret = new byte[32];
        new java.security.SecureRandom().nextBytes(randomSecret);
        var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims("hmac-user").build());
        jwt.sign(new com.nimbusds.jose.crypto.MACSigner(randomSecret));
        assertEquals(401, request(endpoint(), jwt.serialize()).statusCode());
    }
    @Test void refreshesPublicSigningKeysForANewKeyId() throws Exception {
        KEY.set(newKey());
        assertEquals(404, request(endpoint(), sign(claims("rotated-key"))).statusCode());
    }
    @Test void limitsSubjectAcrossTokensAndRejectsHeaderSpoofing() throws Exception {
        String subject = "limited";
        String first = sign(claims(subject));
        String second = sign(claims(subject));
        assertEquals(404, request(endpoint(), first).statusCode());
        assertEquals(404, request(endpoint(), second).statusCode());
        var spoofed = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + endpoint()))
            .header("Authorization", "Bearer " + second).header("X-Forwarded-For", "192.0.2.1").GET().build();
        var response = http.send(spoofed, HttpResponse.BodyHandlers.ofString());
        assertEquals(429, response.statusCode());
        assertEquals("1", response.headers().firstValue("Retry-After").orElseThrow());
        assertEquals(429, mapper.readTree(response.body()).path("status").asInt());
        assertEquals("0", response.headers().firstValue("X-RateLimit-Remaining").orElseThrow());
        assertEquals(404, request(endpoint(), sign(claims("separate-user"))).statusCode());
        assertEquals(401, request(endpoint(), "invalid.token.value").statusCode());
        assertEquals(200, request("/v3/api-docs", null).statusCode());
        assertTrue(response.headers().allValues("Set-Cookie").isEmpty());
    }
    @Test void reconciliationAuthorityDoesNotInheritCustomerOrOperatorAccess() throws Exception {
        String path = "/api/v1/ops/reconciliation";
        assertEquals(401, request(path, null).statusCode());
        for (String role : List.of("CUSTOMER", "OPERATOR")) {
            assertEquals(403, request(path, sign(claims("reconciliation-" + role).claim("roles", List.of(role)))).statusCode());
        }
        // Disabled by default: authorized users still have no aggregate endpoint.
        assertEquals(404, request(path, sign(claims("reconciliation-reader").claim("roles", List.of("RECONCILER")))).statusCode());
        assertEquals(403, request(endpoint(), sign(claims("reconciliation-transfer-denied").claim("roles", List.of("RECONCILER")))).statusCode());
    }
    @Test void rejectsMissingAuthenticationAndInsufficientRoles() throws Exception {
        assertEquals(401, request(endpoint(), null).statusCode());
        assertEquals(403, request(endpoint(), sign(claims("no-role").claim("roles", List.of("AUDITOR")))).statusCode());
        assertEquals(403, request(endpoint(), sign(claims("missing-role").claim("roles", null))).statusCode());
    }
    @Test void providerSubjectStillControlsAccountOwnership() throws Exception {
        String ownerToken = sign(claims("real-owner").claim("roles", List.of("CUSTOMER")));
        var create = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/accounts"))
            .header("Authorization", "Bearer " + ownerToken).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{\"currency\":\"USD\",\"type\":\"CUSTOMER\"}")).build();
        var created = http.send(create, HttpResponse.BodyHandlers.ofString());
        assertEquals(201, created.statusCode());
        String path = "/api/v1/accounts/" + mapper.readTree(created.body()).path("id").asText();
        assertEquals(200, request(path, ownerToken).statusCode());
        var forbidden = request(path, sign(claims("different-owner").claim("roles", List.of("CUSTOMER"))));
        assertEquals(403, forbidden.statusCode());
        assertTrue(forbidden.headers().firstValue("Content-Type").orElse("").startsWith("application/problem+json"));
    }
    @Test void servesRealOpenApiAndSwaggerWithOAuth2AndProblemContracts() throws Exception {
        var response = request("/v3/api-docs", null);
        assertEquals(200, response.statusCode());
        var api = mapper.readTree(response.body());
        assertTrue(api.path("components").path("securitySchemes").has("bearerAuth"));
        var flow = api.path("components").path("securitySchemes").path("oauth2").path("flows").path("authorizationCode");
        assertEquals(ISSUER + "/authorize", flow.path("authorizationUrl").asText());
        assertEquals(ISSUER + "/token", flow.path("tokenUrl").asText());
        assertEquals(2, api.path("security").size());
        var operation = api.path("paths").path("/api/v1/transfers").path("post");
        assertTrue(operation.path("responses").path("429").path("headers").has("Retry-After"));
        assertTrue(operation.path("responses").path("401").path("content").has("application/problem+json"));
        assertTrue(operation.path("responses").has("201"));
        assertTrue(operation.path("responses").has("200"));
        assertTrue(operation.path("responses").has("422"));
        assertTrue(operation.path("parameters").toString().contains("Idempotency-Key"));
        assertEquals(200, request("/swagger-ui/index.html", null).statusCode());
        var config = mapper.readTree(request("/v3/api-docs/swagger-config", null).body());
        assertTrue(config.has("persistAuthorization"));
        assertFalse(config.path("persistAuthorization").asBoolean());
        String initializer = request("/swagger-ui/swagger-initializer.js", null).body();
        assertTrue(java.util.regex.Pattern.compile("usePkceWithAuthorizationCodeGrant[\"'\\s:]+true").matcher(initializer).find());
    }
}
