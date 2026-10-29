package com.keevo.shared.event.catalog.sale;

import com.keevo.shared.event.catalog.DomainEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * SaleAutoValidatedEvent — published when a pending sale is auto-validated
 * because all referenced products transitioned to ACTIVE with stock > 0.
 * Story 4.3 AC7.
 */
public record SaleAutoValidatedEvent(
        UUID saleId,
        UUID triggerProductId,
        UUID actorId,
        String tenantId,
        Instant occurredAt,
        UUID storeId          // v1s-18-6: store the action happened in (audit store_id)
) implements DomainEvent {}
