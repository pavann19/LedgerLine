package com.ledgerline.projection.security;

import tools.jackson.databind.ObjectMapper;
import org.springframework.core.env.Environment;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;


@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SecurityConfig {
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, ObjectMapper objectMapper, Environment environment) throws Exception {
        http.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        http.requestCache(cache -> cache.disable());
        if (environment.getProperty("projection.security.rate-limit.enabled", Boolean.class, true)) {
            int burst = environment.getProperty("projection.security.rate-limit.burst", Integer.class, 1000);
            var limiter = new PrincipalRateLimiter(
                environment.getProperty("projection.security.rate-limit.requests-per-second", Integer.class, 500), burst,
                environment.getProperty("projection.security.rate-limit.max-principals", Integer.class, 10000),
                environment.getProperty("projection.security.rate-limit.idle-seconds", Long.class, 300L), System::nanoTime);
            http.addFilterAfter(new PrincipalRateLimitFilter(limiter, objectMapper, burst), AuthorizationFilter.class);
        }
        return http.csrf(csrf -> csrf.disable())
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                .requestMatchers("/api/v1/**").hasAnyRole("CUSTOMER", "OPERATOR")
                .anyRequest().denyAll())
            .exceptionHandling(errors -> errors
                .authenticationEntryPoint((request, response, exception) -> {
                    response.setHeader("WWW-Authenticate", "Bearer");
                    writeProblem(objectMapper, response, 401, "Unauthorized", "A valid bearer token is required");
                })
                .accessDeniedHandler((request, response, exception) -> writeProblem(objectMapper, response, 403, "Forbidden", "The authenticated principal cannot access this resource")))
            .oauth2ResourceServer(oauth -> oauth
                .authenticationEntryPoint((request, response, exception) -> {
                    response.setHeader("WWW-Authenticate", "Bearer");
                    writeProblem(objectMapper, response, 401, "Unauthorized", "A valid bearer token is required");
                })
                .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())))
            .build();
    }

    @Bean
    JwtDecoder configuredJwtDecoder(Environment environment) {
        String prefix = "projection.security.";
        return JwtDecoderFactory.create(environment.getProperty(prefix + "mode", "local-jwt"),
            environment.getProperty(prefix + "jwt-secret", ""), environment.getProperty(prefix + "issuer-uri", ""),
            environment.getProperty(prefix + "jwk-set-uri", ""), environment.getProperty(prefix + "audience", ""));
    }

    // Kept as a small local-mode factory for the existing claim-boundary regressions.
    JwtDecoder jwtDecoder(String secret) {
        return JwtDecoderFactory.create("local-jwt", secret, "", "", "");
    }

    private Converter<Jwt, ? extends AbstractAuthenticationToken> jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("roles");
        authorities.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }

    private void writeProblem(ObjectMapper mapper, jakarta.servlet.http.HttpServletResponse response, int status, String title, String detail) throws java.io.IOException {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(org.springframework.http.HttpStatusCode.valueOf(status), detail);
        problem.setTitle(title);
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        mapper.writeValue(response.getOutputStream(), problem);
    }
}
