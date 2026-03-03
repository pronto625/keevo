package com.keevo.identity.auth.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * UserRegisteredEvent — Domain event emitted when a new user registers.
 *
 * <p>Pure Java record — NO Spring, JPA, or framework imports.
 * Observer pattern: captured by {@code AuditEventListener}.
 * Naming: {Entity}{PastTense}Event per architecture convention.
 */
public record UserRegisteredEvent(
    UUID userId,
    UUID tenantId,
    String tenantCode,
    String schemaName,
    Instant occurredAt
) {
    /** Factory method for creating the event with current timestamp. */
    public static UserRegisteredEvent of(UUID userId, UUID tenantId,
                                         String tenantCode, String schemaName) {
        return new UserRegisteredEvent(userId, tenantId, tenantCode, schemaName, Instant.now());
    }
}
