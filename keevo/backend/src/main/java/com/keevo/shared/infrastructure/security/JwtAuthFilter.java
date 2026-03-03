package com.keevo.shared.infrastructure.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * JwtAuthFilter — Stub for JWT authentication filter.
 *
 * <p>Extracts bearer token, validates it, sets {@link TenantContext},
 * and populates Spring Security context.
 *
 * <p>Full implementation in Story 1.3.
 */
public class JwtAuthFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        // TODO (Story 1.3): Extract and validate JWT, set TenantContext and SecurityContext
        filterChain.doFilter(request, response);
    }
}
