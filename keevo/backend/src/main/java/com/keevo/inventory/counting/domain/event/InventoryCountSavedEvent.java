package com.keevo.inventory.counting.domain.event;

import java.time.Instant;
import java.util.UUID;

/**
 * InventoryCountSavedEvent — published when a physical count is saved.
 * GoF Observer: AuditEventListener records this for the immutable audit trail.
 */
public record InventoryCountSavedEvent(
    UUID countId,
    UUID sessionId,
    UUID productId,
    UUID variantId,
    int theoretical,
    int physical,
    int ecart,
    UUID actorId,
    String tenantId,
    Instant occurredAt
) {}
