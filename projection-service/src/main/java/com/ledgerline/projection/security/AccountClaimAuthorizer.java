package com.ledgerline.projection.security;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

@Component
public class AccountClaimAuthorizer {
    public void requireAccess(Authentication authentication, UUID accountId) {
        boolean operator = authentication.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_OPERATOR"));
        if (operator) return;
        if (authentication instanceof JwtAuthenticationToken jwt) {
            List<String> accounts = jwt.getToken().getClaimAsStringList("accounts");
            if (accounts != null && accounts.contains(accountId.toString())) return;
        }
        throw new AccessDeniedException("Account is not assigned to the authenticated principal");
    }
}
