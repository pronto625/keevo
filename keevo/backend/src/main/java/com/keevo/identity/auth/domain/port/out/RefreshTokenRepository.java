package com.keevo.identity.auth.domain.port.out;

import com.keevo.identity.auth.domain.model.RefreshToken;

import java.util.Optional;
import java.util.UUID;

/**
 * RefreshTokenRepository — Driven port for refresh token persistence.
 *
 * <p>Interface — implemented in the persistence adapter layer.
 * Tokens are stored in the per-tenant schema (refresh_tokens table).
 */
public interface RefreshTokenRepository {

    /** Persist a new refresh token. */
    RefreshToken save(RefreshToken token);

    /** Find a refresh token by its bcrypt hash. */
    Optional<RefreshToken> findByHash(String hash);

    /** Revoke all refresh tokens for a user (e.g., on logout or password change). */
    void revokeAllByUserId(UUID userId);
}
