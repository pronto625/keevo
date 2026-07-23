package com.keevo.identity.auth.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * PasswordResetEvent — Published when a user successfully resets their password.
 *
 * <p>Story 14.12 — Package {@code identity.auth.domain.model} (D7 convention).
 *
 * <p>Only contains userId (no phone, no new password — anti-PII/hash exposure).
 *
 * @param userId     user who reset their password
 * @param occurredAt when the reset was completed
 */
public record PasswordResetEvent(
        UUID userId,
        Instant occurredAt
) {}
