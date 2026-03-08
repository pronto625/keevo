package com.keevo.catalog.product.domain.event;

import java.time.Instant;
import java.util.UUID;

/**
 * ProductUpdatedEvent — Domain event published after successful product update.
 *
 * <p>GoF Pattern: Observer — for audit trail with valueBefore/valueAfter tracking.
 * 
 * <p>Contains original and updated product data as JSON strings for audit log.
 * 
 * <p>Story 2.1 — Product CRUD with audit trail integration.
 */
public record ProductUpdatedEvent(
        UUID productId,
        String productName,
        String valueBefore,  // JSON of product before update
        String valueAfter,   // JSON of product after update
        String tenantId,
        UUID actorId,
        Instant occurredAt
) {}