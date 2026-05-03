package com.keevo.commerce.sale.domain.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * SaleDraftProductsUpgradedEvent — published when a sale containing originally-draft
 * products is recorded as COMPLETED (new flow — no more PENDING_VALIDATION).
 *
 * <p>The Flutter client asks for initial stock at checkout time, queues
 * RECORD_STOCK_ENTRY + PROMOTE_PRODUCT ops before CREATE_SALE, and includes
 * this list in the CREATE_SALE payload so the backend can notify the owner.
 */
public record SaleDraftProductsUpgradedEvent(
        UUID saleId,
        UUID storeId,
        UUID actorId,
        List<UUID> originalDraftProductIds,
        int totalAmount,
        String tenantId,
        Instant occurredAt
) {}
