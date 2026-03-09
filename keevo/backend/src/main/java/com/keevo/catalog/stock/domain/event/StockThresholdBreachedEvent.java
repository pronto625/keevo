package com.keevo.catalog.stock.domain.event;

import java.time.Instant;
import java.util.UUID;

/**
 * StockThresholdBreachedEvent — emitted when stock drops to or below the
 * configured minimum threshold after any stock-modifying operation.
 *
 * <p>Story 2.3: this event is emitted and audit-logged only.
 * FCM push notification delivery is deferred to Epic 8 (story 8-1).
 *
 * <p>GoF: Observer — decouples threshold logic from notification infrastructure.
 */
public record StockThresholdBreachedEvent(
        UUID productId,
        String productName,
        UUID storeId,
        int currentQuantity,
        int threshold,
        UUID actorId,
        String tenantId,
        Instant occurredAt
) {}
