package com.ledgerline.ledger.api;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.*;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.headers.Header;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import java.net.URI;

@Configuration
public class OpenApiConfig {
    @Bean
    OpenAPI apiDefinition(Environment environment) {
        var api = new OpenAPI().info(new Info().title("LedgerLine Ledger API").version("v1")
            .description("Money is expressed in integer minor units. Authorization uses signed roles and account ownership. "
                + "Rate limits are per authenticated subject and service instance; X-RateLimit-Limit reports burst capacity."));
        var components = new Components().addSecuritySchemes("bearerAuth", new SecurityScheme()
            .type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT"));
        api.components(components).addSecurityItem(new SecurityRequirement().addList("bearerAuth"));
        String authorizationUrl = environment.getProperty("ledger.security.oauth2.authorization-url", "");
        String tokenUrl = environment.getProperty("ledger.security.oauth2.token-url", "");
        if (!authorizationUrl.isBlank() || !tokenUrl.isBlank()) {
            if (!"oauth2".equals(environment.getProperty("ledger.security.mode", "local-jwt"))) {
                throw new IllegalArgumentException("OAuth2 documentation requires security.mode=oauth2");
            }
            requireProviderUrl(authorizationUrl);
            requireProviderUrl(tokenUrl);
            components.addSecuritySchemes("oauth2", new SecurityScheme().type(SecurityScheme.Type.OAUTH2)
                .flows(new OAuthFlows().authorizationCode(new OAuthFlow().authorizationUrl(authorizationUrl)
                    .tokenUrl(tokenUrl).scopes(new Scopes().addString("openid", "Provider sign-in; API access uses roles and accounts claims")))));
            // Separate security items mean alternatives, rather than requiring both schemes together.
            api.addSecurityItem(new SecurityRequirement().addList("oauth2"));
        }
        return api;
    }

    @Bean
    OpenApiCustomizer apiErrors() {
        return api -> {
            if (api.getPaths() == null) return;
            api.getPaths().forEach((path, item) -> {
                if (!path.startsWith("/api/v1/")) return;
                item.readOperations().forEach(operation -> {
                    var responses = operation.getResponses();
                    addError(responses, "400", "Malformed or invalid request");
                    addError(responses, "401", "Missing, expired or invalid bearer token");
                    addError(responses, "403", "Role or account ownership does not permit access");
                    addError(responses, "429", "Per-principal quota exhausted or limiter identity capacity reached");
                    responses.get("429").addHeaderObject("Retry-After", new Header().description("Whole seconds to wait before retrying")
                        .schema(new IntegerSchema().minimum(java.math.BigDecimal.ONE)));
                    addError(responses, "500", "Internal error");
                });
            });
        };
    }

    private static void addError(io.swagger.v3.oas.models.responses.ApiResponses responses, String code, String description) {
        if (responses.containsKey(code)) return;
        var schema = new Schema<>().type("object")
            .addProperty("type", new Schema<>().type("string"))
            .addProperty("title", new Schema<>().type("string"))
            .addProperty("status", new IntegerSchema())
            .addProperty("detail", new Schema<>().type("string"))
            .addProperty("instance", new Schema<>().type("string"));
        var content = new Content().addMediaType("application/problem+json", new MediaType().schema(schema));
        responses.addApiResponse(code, new ApiResponse().description(description).content(content));
    }

    private static void requireProviderUrl(String value) {
        URI uri = URI.create(value);
        boolean loopback = java.util.List.of("localhost", "127.0.0.1", "[::1]", "::1").contains(uri.getHost() == null ? "" : uri.getHost());
        if (uri.getHost() == null || uri.getRawUserInfo() != null || uri.getFragment() != null
            || !("https".equals(uri.getScheme()) || ("http".equals(uri.getScheme()) && loopback))) {
            throw new IllegalArgumentException("OAuth2 documentation needs HTTPS authorization and token URLs (loopback HTTP allowed)");
        }
    }
}
