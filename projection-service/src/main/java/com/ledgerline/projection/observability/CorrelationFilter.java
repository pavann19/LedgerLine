package com.ledgerline.projection.observability;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.UUID;

@Component
public class CorrelationFilter extends OncePerRequestFilter {
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)
        throws ServletException,IOException {
        // Always generate an application-owned ID; do not echo arbitrary headers.
        String id=UUID.randomUUID().toString();
        MDC.put("request_id",id); response.setHeader("X-Correlation-ID",id);
        try { chain.doFilter(request,response); } finally { MDC.remove("request_id"); }
    }
}
