package com.keevo.shared.event.catalog.product;

import com.keevo.shared.event.catalog.DomainEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * ProductActivatedEvent — published when a product transitions from DRAFT to ACTIVE.
 * Story 4.3 AC7 — triggers cascade auto-validation of pending sales.
 */
public record ProductActivatedEvent(
        UUID productId,
        UUID actorId,
        String tenantId,
        Instant occurredAt
) implements DomainEvent {}
