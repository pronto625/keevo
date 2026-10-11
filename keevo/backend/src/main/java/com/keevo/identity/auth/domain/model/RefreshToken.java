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
 * @param revoked    whether the token can no longer be presented as-is
 * @param replacedById successor issued when this token was exchanged; null if never
 *                   exchanged (a revoked token without successor was revoked outright,
 *                   or superseded)
 * @param rotatedAt  when this token was first exchanged; for a superseded token, when
 *                   it was superseded
 */
public record RefreshToken(
    UUID id,
    UUID userId,
    String tenantId,
    String tokenHash,
    Instant expiresAt,
    boolean revoked,
    UUID replacedById,
    Instant rotatedAt
) {
    /** Token that has never been exchanged. */
    public RefreshToken(UUID id, UUID userId, String tenantId, String tokenHash,
                        Instant expiresAt, boolean revoked) {
        this(id, userId, tenantId, tokenHash, expiresAt, revoked, null, null);
    }

    /** Factory — creates a new non-revoked refresh token. */
    public static RefreshToken create(UUID userId, String tenantId,
                                     String tokenHash, Instant expiresAt) {
        return new RefreshToken(UUID.randomUUID(), userId, tenantId, tokenHash, expiresAt, false);
    }

    /** True when this token was exchanged for {@link #replacedById} (as opposed to revoked). */
    public boolean isRotated() {
        return replacedById != null;
    }

    /** This token after being exchanged for {@code successorId}: no longer usable as-is. */
    public RefreshToken rotatedTo(UUID successorId, Instant at) {
        return new RefreshToken(id, userId, tenantId, tokenHash, expiresAt, true, successorId, at);
    }

    /**
     * This rotated token pointing to a new successor after a lost-response recovery.
     * {@link #rotatedAt} is kept: the recovery window counts from the first exchange.
     */
    public RefreshToken withSuccessor(UUID successorId) {
        return new RefreshToken(id, userId, tenantId, tokenHash, expiresAt, true, successorId, rotatedAt);
    }

    /**
     * This unused successor replaced by a recovery from its predecessor. Presenting it
     * later means two parties hold the session.
     */
    public RefreshToken supersededAt(Instant at) {
        return new RefreshToken(id, userId, tenantId, tokenHash, expiresAt, true, null, at);
    }

    /** True when a recovery from the predecessor replaced this token before it was used. */
    public boolean isSuperseded() {
        return revoked && replacedById == null && rotatedAt != null;
    }

    /** This token revoked outright (it was issued but never reached the client). */
    public RefreshToken revokedOutright() {
        return new RefreshToken(id, userId, tenantId, tokenHash, expiresAt, true, null, null);
    }
}
