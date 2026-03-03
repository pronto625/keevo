package com.keevo.shared.infrastructure.security;

import org.springframework.stereotype.Component;

/**
 * JwtTokenProvider — Stub for JWT token generation and validation.
 *
 * <p>Full implementation in Story 1.3 (JWT authentication).
 * This stub exists to satisfy the compiler and establish the API contract.
 */
@Component
public class JwtTokenProvider {

    /**
     * Generate a signed JWT token for the given subject and tenant.
     *
     * @param subject  user identifier (UUID)
     * @param tenantId tenant identifier
     * @return signed JWT string
     */
    public String generateToken(String subject, String tenantId) {
        // STUB — Story 1.3 implements real JWT (RS256, 24h expiry)
        // Returns a clearly-marked placeholder token for Story 1.2
        return "STUB:" + subject + ":" + tenantId;
    }

    public boolean validateToken(String token) {
        // STUB — Story 1.3 implements validation
        return token != null && token.startsWith("STUB:");
    }

    public String getSubject(String token) {
        // STUB — Story 1.3 implements parsing
        if (token != null && token.startsWith("STUB:")) {
            String[] parts = token.split(":");
            return parts.length > 1 ? parts[1] : null;
        }
        return null;
    }

    public String getTenantId(String token) {
        // STUB — Story 1.3 implements parsing
        if (token != null && token.startsWith("STUB:")) {
            String[] parts = token.split(":");
            return parts.length > 2 ? parts[2] : null;
        }
        return null;
    }
}
