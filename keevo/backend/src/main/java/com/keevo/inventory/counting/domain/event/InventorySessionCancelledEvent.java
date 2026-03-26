package com.keevo.inventory.counting.domain.event;

import java.time.Instant;
import java.util.UUID;

/**
 * InventorySessionCancelledEvent — Published when an inventory session is cancelled.
 *
 * <p>GoF Observer — consumed by AuditEventListener for audit logging.
 */
public record InventorySessionCancelledEvent(
        UUID sessionId,
        UUID actorId,
        String tenantId,
        Instant occurredAt
) {}
