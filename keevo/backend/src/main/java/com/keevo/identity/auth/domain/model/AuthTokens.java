package com.keevo.identity.auth.domain.model;

import java.util.UUID;

/**
 * AuthTokens — Domain model returned after successful authentication.
 *
 * <p>Pure Java record — NO Spring, JPA, or framework imports.
 *
 * @param accessToken              RS256-signed JWT (24h expiry)
 * @param refreshToken             opaque random bytes, base64url-encoded (30-day expiry)
 * @param expiresIn                access token lifetime in seconds
 * @param userId                   authenticated user's UUID (used by REST adapter to build response)
 * @param tenantId                 tenant schema name (kv_xxxxxx) for client routing
 * @param role                     user role string (OWNER | EMPLOYEE)
 * @param storeId                  assigned store UUID string (null for OWNER) — Story 3.5
 * @param passwordChangeRequired   true if employee must change password — Story 3.5
 */
public record AuthTokens(
    String accessToken,
    String refreshToken,
    long   expiresIn,
    UUID   userId,
    String tenantId,
    String role,
    String storeId,
    boolean passwordChangeRequired
) {
    /** Backward-compatible constructor for OWNER tokens (storeId=null, passwordChangeRequired=false). */
    public AuthTokens(String accessToken, String refreshToken, long expiresIn,
                      UUID userId, String tenantId, String role) {
        this(accessToken, refreshToken, expiresIn, userId, tenantId, role, null, false);
    }
}
