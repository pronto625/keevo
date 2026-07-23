package com.keevo.identity.auth.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * PasswordResetRequestedEvent — Published when a user requests password reset.
 *
 * <p>Story 14.12 — Package {@code identity.auth.domain.model} (D7 convention:
 * same package as {@link UserRegisteredEvent} and {@link UserAuthenticatedEvent}).
 *
 * <p>Contains the userId and phoneNumber for the reset request.
 * Audit listener will not log the phone number (anti-PII, AC5).
 *
 * @param userId      user who requested the reset
 * @param phoneNumber phone number (for audit only — never in payload)
 * @param occurredAt  when the event was published
 */
public record PasswordResetRequestedEvent(
        UUID userId,
        String phoneNumber,
        Instant occurredAt
) {}
