package com.keevo.shared.event.catalog.counting;

import com.keevo.shared.event.catalog.DomainEvent;


import java.time.Instant;
import java.util.UUID;

/**
 * InventorySessionCreatedEvent — Published when a new inventory session is started.
 *
 * <p>GoF Observer — consumed by AuditEventListener for audit logging.
 */
public record InventorySessionCreatedEvent(
        UUID sessionId,
        UUID storeId,
        InventoryScopeKind scope,
        UUID actorId,
        String tenantId,
        Instant occurredAt
) implements DomainEvent {}
