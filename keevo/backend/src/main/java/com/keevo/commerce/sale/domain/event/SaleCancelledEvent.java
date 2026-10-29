package com.keevo.commerce.sale.domain.event;

import java.time.Instant;
import java.util.UUID;

/**
 * SaleCancelledEvent — published when a sale is cancelled by the owner.
 * Story 4.3 AC10 (PENDING). Story v1s-13-5 extends to COMPLETED with stock restoration
 * — {@code itemsSnapshot} carries the cancelled items as JSON (pattern mirrors
 * {@link SaleCompletedEvent#getItemsSnapshot()}), always a valid JSON string, never null.
 */
public record SaleCancelledEvent(
        UUID saleId,
        UUID actorId,
        String justification,
        String itemsSnapshot,
        String tenantId,
        Instant occurredAt,
        UUID storeId          // v1s-18-6: store the action happened in (audit store_id)
) {}
