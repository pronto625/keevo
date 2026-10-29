package com.keevo.shared.event.catalog.counting;

import com.keevo.shared.event.catalog.DomainEvent;

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
    Instant occurredAt,
    UUID storeId          // v1s-18-6: store the action happened in (audit store_id)
) implements DomainEvent {}
