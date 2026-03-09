package com.keevo.catalog.stock.domain.event;

import com.keevo.catalog.stock.domain.entity.MovementType;

import java.time.Instant;
import java.util.UUID;

/**
 * StockAdjustedEvent — emitted after any stock-modifying operation.
 *
 * <p>Consumed by {@link com.keevo.shared.infrastructure.web.AuditEventListener}
 * to write an immutable audit_log entry (Story 2.3).
 *
 * <p>GoF: Observer — published via Spring ApplicationEventPublisher,
 * decoupling domain from infrastructure.
 */
public record StockAdjustedEvent(
        UUID productId,
        UUID variantId,        // nullable
        UUID storeId,
        MovementType movementType,
        int quantityBefore,
        int quantityChange,
        int quantityAfter,
        UUID actorId,
        String notes,          // nullable
        String tenantId,
        Instant occurredAt
) {}
