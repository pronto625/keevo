package com.keevo.identity.auth.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * PasswordResetRequestedEvent — Published when a user requests password reset.
 *
 * <p>Story 14.12 — Package {@code identity.auth.domain.model} (D7 convention:
 * same package as {@link UserRegisteredEvent} and {@link UserAuthenticatedEvent}).
 *
 * <p>Story 12.12 — carries no phone number (PII): consumers only need the userId.
 *
 * @param userId      user who requested the reset
 * @param occurredAt  when the event was published
 */
@org.springframework.modulith.NamedInterface("events")
public record PasswordResetRequestedEvent(
        UUID userId,
        Instant occurredAt
) {}
