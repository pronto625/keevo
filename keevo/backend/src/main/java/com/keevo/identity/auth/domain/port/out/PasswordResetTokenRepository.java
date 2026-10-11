package com.keevo.identity.auth.domain.port.out;

import com.keevo.identity.auth.domain.model.PasswordResetToken;

import java.time.Instant;
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

    /**
     * Story 12.12 — atomically claim one verification attempt:
     * {@code attempts = attempts + 1 WHERE id = ? AND consumed_at IS NULL AND attempts < maxAttempts}.
     *
     * @return {@code true} if the attempt was claimed, {@code false} if the token is locked
     *         (cap reached) or already consumed — concurrent guesses can never exceed the cap
     */
    boolean tryClaimAttempt(UUID tokenId, int maxAttempts);

    /**
     * Story 12.12 — compare-and-set consumption ({@code WHERE consumed_at IS NULL}).
     *
     * @return {@code true} only for the single caller that consumed the token
     */
    boolean markConsumed(UUID tokenId, Instant consumedAt);

    /** Story 12.12 — delete tokens whose {@code expires_at} is before the cutoff; returns rows deleted. */
    int purgeExpiredBefore(Instant cutoff);
}
