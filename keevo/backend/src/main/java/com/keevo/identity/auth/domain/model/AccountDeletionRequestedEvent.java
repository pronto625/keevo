package com.keevo.identity.auth.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * AccountDeletionRequestedEvent — Domain event emitted when an OWNER requests
 * account deletion (Story 14.5, FR91).
 *
 * <p>Pure Java record — NO Spring, JPA, or framework imports.
 * Observer pattern: captured by {@code AuditEventListener}.
 */
public record AccountDeletionRequestedEvent(
    UUID tenantId,
    UUID actorId,
    Instant requestedAt,
    Instant scheduledDeletionAt
) {
    public static AccountDeletionRequestedEvent of(UUID tenantId, UUID actorId,
                                                    Instant scheduledDeletionAt) {
        return new AccountDeletionRequestedEvent(tenantId, actorId, Instant.now(), scheduledDeletionAt);
    }
}
