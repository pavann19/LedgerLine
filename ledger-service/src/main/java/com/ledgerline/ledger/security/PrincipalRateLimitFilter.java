package com.ledgerline.ledger.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;

/** Runs only inside the security chain, after authentication and authorization. */
final class PrincipalRateLimitFilter extends OncePerRequestFilter {
    private final PrincipalRateLimiter limiter;
    private final ObjectMapper mapper;
    private final int burst;

    PrincipalRateLimitFilter(PrincipalRateLimiter limiter, ObjectMapper mapper, int burst) {
        this.limiter = limiter;
        this.mapper = mapper;
        this.burst = burst;
    }
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().substring(request.getContextPath().length()).startsWith("/api/v1/");
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            chain.doFilter(request, response);
            return;
        }
        var decision = limiter.acquire(authentication.getName());
        response.setHeader("X-RateLimit-Limit", Integer.toString(burst));
        response.setHeader("X-RateLimit-Remaining", Integer.toString(decision.remaining()));
        if (!decision.allowed()) {
            response.setStatus(429);
            response.setHeader("Retry-After", Long.toString(decision.retryAfterSeconds()));
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            var problem = ProblemDetail.forStatusAndDetail(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS, "Request quota exhausted; retry after the indicated delay");
            problem.setTitle("Too Many Requests");
            problem.setType(URI.create("https://ledgerline.dev/problems/rate-limit-exceeded"));
            problem.setInstance(URI.create(request.getRequestURI()));
            mapper.writeValue(response.getOutputStream(), problem);
            return;
        }
        chain.doFilter(request, response);
    }
}
