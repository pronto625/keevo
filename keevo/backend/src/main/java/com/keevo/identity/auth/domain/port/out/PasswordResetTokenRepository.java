package com.keevo.identity.auth.domain.port.out;

import com.keevo.identity.auth.domain.model.PasswordResetToken;

import java.util.Optional;
import java.util.UUID;

/**
 * PasswordResetTokenRepository — Driven port for password reset token persistence.
 *
 * <p>Story 14.12 — Operations target the PUBLIC schema (global user registry).
 */
public interface PasswordResetTokenRepository {

    /** Persist a new password reset token. */
    PasswordResetToken save(PasswordResetToken token);

    /**
     * Find the most recent active (non-consumed) token for the given phone number.
     * Returns empty if no active token exists.
     */
    Optional<PasswordResetToken> findActiveByPhoneNumber(String phoneNumber);

    /**
     * Invalidate ALL active (non-consumed) tokens for a given user.
     * Sets consumed_at = NOW() on all matching rows.
     */
    void invalidateActiveTokensForUser(UUID userId);
}
