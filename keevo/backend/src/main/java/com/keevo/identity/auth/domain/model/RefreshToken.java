package com.keevo.identity.auth.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * RefreshToken — Domain model for a refresh token stored in the DB.
 *
 * <p>Pure Java record — NO Spring, JPA, or framework imports.
 * The raw token is NEVER stored — only its bcrypt hash.
 *
 * @param id         unique refresh token ID
 * @param userId     owner user ID
 * @param tenantId   tenant identifier (schema name)
 * @param tokenHash  bcrypt hash of the raw opaque token
 * @param expiresAt  absolute expiry (30 days from issuance)
 * @param revoked    whether the token has been revoked
 */
public record RefreshToken(
    UUID id,
    UUID userId,
    String tenantId,
    String tokenHash,
    Instant expiresAt,
    boolean revoked
) {
    /** Factory — creates a new non-revoked refresh token. */
    public static RefreshToken create(UUID userId, String tenantId,
                                     String tokenHash, Instant expiresAt) {
        return new RefreshToken(UUID.randomUUID(), userId, tenantId, tokenHash, expiresAt, false);
    }
}
