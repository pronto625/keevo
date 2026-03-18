package com.keevo.commerce.sale.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * StockForcedZeroEvent — published when manual validation forces stock to 0
 * because the product had insufficient quantity.
 * Story 4.3 AC9.
 */
public record StockForcedZeroEvent(
        UUID productId,
        UUID storeId,
        int requestedQuantity,
        int availableQuantity,
        UUID actorId,
        String tenantId,
        Instant occurredAt
) {}
