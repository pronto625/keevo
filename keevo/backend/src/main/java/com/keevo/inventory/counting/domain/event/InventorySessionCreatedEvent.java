package com.keevo.inventory.counting.domain.event;

import com.keevo.inventory.counting.domain.model.InventoryScope;

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
        InventoryScope scope,
        UUID actorId,
        String tenantId,
        Instant occurredAt
) {}
