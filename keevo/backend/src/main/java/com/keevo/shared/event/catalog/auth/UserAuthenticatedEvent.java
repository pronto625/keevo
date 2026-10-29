package com.keevo.shared.event.catalog.auth;

import com.keevo.shared.event.catalog.DomainEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * UserAuthenticatedEvent — Domain event published after successful login.
 *
 * <p>GoF Pattern: Observer — published by AuthenticationService,
 * consumed by AuditEventListener (no coupling between them).
 *
 * @param userId      authenticated user
 * @param tenantId    tenant code (e.g., "KV-ABC123")
 * @param role        user role at authentication time
 * @param ipAddress   client IP address (nullable — not always available)
 * @param occurredAt  UTC instant of authentication
 */
public record UserAuthenticatedEvent(
    UUID userId,
    String tenantId,
    String role,
    String ipAddress,
    Instant occurredAt
) implements DomainEvent {

    /** Envelope (Story 12.3): the authenticated user is the actor. Not a payload component. */
    @Override
    public UUID actorId() {
        return userId;
    }
}
