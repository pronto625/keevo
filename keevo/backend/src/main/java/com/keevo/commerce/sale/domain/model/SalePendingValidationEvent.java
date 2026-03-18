package com.keevo.commerce.sale.domain.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * SalePendingValidationEvent — published when a sale is recorded with PENDING_VALIDATION status.
 * Story 4.3 AC3.
 */
public record SalePendingValidationEvent(
        UUID saleId,
        UUID storeId,
        UUID actorId,
        List<UUID> draftProductIds,
        int totalAmount,
        String tenantId,
        Instant occurredAt
) {}
