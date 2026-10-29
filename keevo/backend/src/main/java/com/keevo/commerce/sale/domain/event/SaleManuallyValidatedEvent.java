package com.keevo.commerce.sale.domain.event;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * SaleManuallyValidatedEvent — published when an owner force-validates a pending sale.
 * Story 4.3 AC9.
 */
public record SaleManuallyValidatedEvent(
        UUID saleId,
        UUID actorId,
        String justification,
        List<UUID> forcedProducts,
        String tenantId,
        Instant occurredAt,
        UUID storeId          // v1s-18-6: store the action happened in (audit store_id)
) {}
