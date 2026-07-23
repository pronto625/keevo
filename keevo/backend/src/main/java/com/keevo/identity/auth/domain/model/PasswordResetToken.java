package com.keevo.identity.auth.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * PasswordResetToken — Domain model for an OTP-based password reset token.
 *
 * <p>Story 14.12 — Réinitialisation mot de passe oublié via OTP WhatsApp.
 * Pure Java record — NO Spring, JPA, or framework imports.
 * JPA mapping lives in {@code adapter/out/persistence/entity/PasswordResetTokenJpaEntity}.
 *
 * <p>Security: only {@code codeHash} (bcrypt) is persisted — the 6-digit OTP code
 * is NEVER stored in plain text (D2).
 */
public record PasswordResetToken(
        UUID id,
        UUID userId,
        String phoneNumber,
        String codeHash,
        Instant expiresAt,
        Instant consumedAt,
        int attempts,
        Instant createdAt
) {
    /** Factory method for creating a new token (generates a fresh UUID). */
    public static PasswordResetToken create(UUID userId, String phoneNumber,
                                             String codeHash, Instant expiresAt) {
        return new PasswordResetToken(
                UUID.randomUUID(),
                Objects.requireNonNull(userId, "userId must not be null"),
                Objects.requireNonNull(phoneNumber, "phoneNumber must not be null"),
                Objects.requireNonNull(codeHash, "codeHash must not be null"),
                Objects.requireNonNull(expiresAt, "expiresAt must not be null"),
                null,   // consumedAt
                0,      // attempts
                Instant.now()
        );
    }

    // ── Withers ──────────────────────────────────────────────────────────

    public PasswordResetToken withAttempts(int newAttempts) {
        return new PasswordResetToken(id, userId, phoneNumber, codeHash,
                expiresAt, consumedAt, newAttempts, createdAt);
    }

    public PasswordResetToken withConsumed(Instant now) {
        return new PasswordResetToken(id, userId, phoneNumber, codeHash,
                expiresAt, now, attempts, createdAt);
    }

    // ── Convenience queries ──────────────────────────────────────────────

    public boolean isConsumed() { return consumedAt != null; }
    public boolean isExpired()  { return Instant.now().isAfter(expiresAt); }
    public boolean isLocked()   { return attempts >= 5; }
}
