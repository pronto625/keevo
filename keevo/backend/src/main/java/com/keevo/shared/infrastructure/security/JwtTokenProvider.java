package com.keevo.shared.infrastructure.security;

/**
 * JwtTokenProvider — Stub for JWT token generation and validation.
 *
 * <p>Full implementation in Story 1.3 (JWT authentication).
 * This stub exists to satisfy the compiler and establish the API contract.
 */
public class JwtTokenProvider {

    /**
     * Generate a signed JWT token for the given subject and tenant.
     *
     * @param subject  user identifier (UUID)
     * @param tenantId tenant identifier
     * @return signed JWT string
     */
    public String generateToken(String subject, String tenantId) {
        // TODO (Story 1.3): Implement JWT generation with JJWT
        throw new UnsupportedOperationException(
                "JwtTokenProvider.generateToken() not yet implemented — Story 1.3");
    }

    /**
     * Validate the token signature and expiry.
     *
     * @param token JWT string
     * @return true if valid
     */
    public boolean validateToken(String token) {
        // TODO (Story 1.3): Implement validation
        throw new UnsupportedOperationException(
                "JwtTokenProvider.validateToken() not yet implemented — Story 1.3");
    }

    /**
     * Extract the subject (user ID) from a token.
     */
    public String getSubject(String token) {
        // TODO (Story 1.3)
        throw new UnsupportedOperationException(
                "JwtTokenProvider.getSubject() not yet implemented — Story 1.3");
    }

    /**
     * Extract the tenant ID claim from a token.
     */
    public String getTenantId(String token) {
        // TODO (Story 1.3)
        throw new UnsupportedOperationException(
                "JwtTokenProvider.getTenantId() not yet implemented — Story 1.3");
    }
}
