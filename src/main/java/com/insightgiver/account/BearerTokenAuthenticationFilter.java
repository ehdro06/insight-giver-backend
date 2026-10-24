package com.insightgiver.account;

import java.io.IOException;
import java.util.List;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates {@code Authorization: Bearer <device token>}. Requests without a valid token stay
 * anonymous and are rejected by the authorization rules, not here.
 */
final class BearerTokenAuthenticationFilter extends OncePerRequestFilter {

    private static final String PREFIX = "Bearer ";

    private final AccountService accounts;
    private final SecurityContextHolderStrategy contexts = SecurityContextHolder.getContextHolderStrategy();

    BearerTokenAuthenticationFilter(AccountService accounts) {
        this.accounts = accounts;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.regionMatches(true, 0, PREFIX, 0, PREFIX.length())) {
            String token = header.substring(PREFIX.length()).trim();
            if (!token.isEmpty()) {
                accounts.authenticate(token).ifPresent(id -> {
                    SecurityContext context = contexts.createEmptyContext();
                    context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                            new AuthenticatedAccount(id), null, List.of()));
                    contexts.setContext(context);
                });
            }
        }
        chain.doFilter(request, response);
    }
}
