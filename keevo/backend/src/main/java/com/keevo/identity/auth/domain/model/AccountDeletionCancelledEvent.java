package com.keevo.identity.auth.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * AccountDeletionCancelledEvent — Domain event emitted when an OWNER cancels
 * a pending account deletion (Story 14.5, FR91).
 *
 * <p>Pure Java record — NO Spring, JPA, or framework imports.
 * Observer pattern: captured by {@code AuditEventListener}.
 */
public record AccountDeletionCancelledEvent(
    UUID tenantId,
    UUID actorId,
    Instant cancelledAt
) {
    public static AccountDeletionCancelledEvent of(UUID tenantId, UUID actorId) {
        return new AccountDeletionCancelledEvent(tenantId, actorId, Instant.now());
    }
}
