package com.keevo.identity.auth.domain.port.out;

import com.keevo.identity.auth.domain.model.RefreshToken;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * RefreshTokenRepository — Driven port for refresh token persistence.
 *
 * <p>Interface — implemented in the persistence adapter layer.
 * Tokens are stored in the global {@code public.refresh_tokens} table (one table for every tenant, rows carry
 * {@code tenant_id}); it is never mirrored into tenant schemas (Story 13.4, global-only table).
 */
public interface RefreshTokenRepository {

    /** Persist a new refresh token. */
    RefreshToken save(RefreshToken token);

    /** Find a refresh token by its bcrypt hash. */
    Optional<RefreshToken> findByHash(String hash);

    Optional<RefreshToken> findById(UUID id);

    /** Revoke all refresh tokens for a user (e.g., on logout or password change). */
    void revokeAllByUserId(UUID userId);

    /**
     * Story 12.12 — delete refresh tokens whose {@code expires_at} is before the cutoff
     * (rotated, revoked or simply expired rows are never accepted again). Returns rows deleted.
     */
    int purgeExpiredBefore(Instant cutoff);
}
