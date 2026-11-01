package com.keevo.identity.auth.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * RefreshToken — Domain model for a refresh token stored in the DB.
 *
 * <p>Pure Java record — NO Spring, JPA, or framework imports.
 * The raw token is NEVER stored — only its SHA-256 hash.
 *
 * <p>Story 16.11 — every token of a device chain shares one {@link #sessionId} (the {@code sid} of
 * its access tokens), carried over on each rotation; {@link #deviceId} / {@link #deviceLabel} come
 * from the app; {@link #lastUsedAt} is the login or renewal that issued the token.
 *
 * @param id           unique refresh token ID
 * @param userId       owner user ID
 * @param tenantId     tenant identifier (schema name)
 * @param tokenHash    SHA-256 hash of the raw opaque token
 * @param expiresAt    absolute expiry (sliding: each rotation issues a new one)
 * @param revoked      whether the token can no longer be presented as-is
 * @param replacedById successor issued when this token was exchanged; null if never
 *                     exchanged (a revoked token without successor was revoked outright,
 *                     or superseded)
 * @param rotatedAt    when this token was first exchanged; for a superseded token, when
 *                     it was superseded
 * @param sessionId    device session (stable across rotations)
 * @param deviceId     device id sent by the app (nullable)
 * @param deviceLabel  readable device label sent by the app (nullable)
 * @param lastUsedAt   when the session was last signed in / renewed with this token
 */
public record RefreshToken(
    UUID id,
    UUID userId,
    String tenantId,
    String tokenHash,
    Instant expiresAt,
    boolean revoked,
    UUID replacedById,
    Instant rotatedAt,
    UUID sessionId,
    String deviceId,
    String deviceLabel,
    Instant lastUsedAt
) {
    /** Token that has never been exchanged, without device session data (legacy / tests). */
    public RefreshToken(UUID id, UUID userId, String tenantId, String tokenHash,
                        Instant expiresAt, boolean revoked) {
        this(id, userId, tenantId, tokenHash, expiresAt, revoked, null, null, id, null, null, null);
    }

    /** Pre-16.11 shape (tests): the token is its own session. */
    public RefreshToken(UUID id, UUID userId, String tenantId, String tokenHash,
                        Instant expiresAt, boolean revoked, UUID replacedById, Instant rotatedAt) {
        this(id, userId, tenantId, tokenHash, expiresAt, revoked, replacedById, rotatedAt, id, null, null, null);
    }

    /** Factory — creates a new non-revoked refresh token that starts its own session. */
    public static RefreshToken create(UUID userId, String tenantId,
                                     String tokenHash, Instant expiresAt) {
        UUID id = UUID.randomUUID();
        return new RefreshToken(id, userId, tenantId, tokenHash, expiresAt, false,
                null, null, id, null, null, Instant.now());
    }

    /** Factory — a new non-revoked refresh token in device session {@code sessionId}. */
    public static RefreshToken create(UUID userId, String tenantId, String tokenHash, Instant expiresAt,
                                      UUID sessionId, String deviceId, String deviceLabel, Instant now) {
        return new RefreshToken(UUID.randomUUID(), userId, tenantId, tokenHash, expiresAt, false,
                null, null, sessionId, deviceId, deviceLabel, now);
    }

    /** The device session of this token (rows created before Story 16.11: the token itself). */
    public UUID effectiveSessionId() {
        return sessionId != null ? sessionId : id;
    }

    /** True when this token was exchanged for {@link #replacedById} (as opposed to revoked). */
    public boolean isRotated() {
        return replacedById != null;
    }

    /** This token after being exchanged for {@code successorId}: no longer usable as-is. */
    public RefreshToken rotatedTo(UUID successorId, Instant at) {
        return new RefreshToken(id, userId, tenantId, tokenHash, expiresAt, true, successorId, at,
                sessionId, deviceId, deviceLabel, lastUsedAt);
    }

    /**
     * This rotated token pointing to a new successor after a lost-response recovery.
     * {@link #rotatedAt} is kept: the recovery window counts from the first exchange.
     */
    public RefreshToken withSuccessor(UUID successorId) {
        return new RefreshToken(id, userId, tenantId, tokenHash, expiresAt, true, successorId, rotatedAt,
                sessionId, deviceId, deviceLabel, lastUsedAt);
    }

    /**
     * This unused successor replaced by a recovery from its predecessor. Presenting it
     * later means two parties hold the session.
     */
    public RefreshToken supersededAt(Instant at) {
        return new RefreshToken(id, userId, tenantId, tokenHash, expiresAt, true, null, at,
                sessionId, deviceId, deviceLabel, lastUsedAt);
    }

    /** True when a recovery from the predecessor replaced this token before it was used. */
    public boolean isSuperseded() {
        return revoked && replacedById == null && rotatedAt != null;
    }

    /** This token revoked outright (it was issued but never reached the client). */
    public RefreshToken revokedOutright() {
        return new RefreshToken(id, userId, tenantId, tokenHash, expiresAt, true, null, null,
                sessionId, deviceId, deviceLabel, lastUsedAt);
    }
}
