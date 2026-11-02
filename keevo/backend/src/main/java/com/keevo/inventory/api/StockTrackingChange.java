package com.keevo.inventory.api;

import java.util.UUID;

/**
 * Input of {@link StockCommandPort#changeTracking} (Story 17.12).
 *
 * @param tracked         the new setting
 * @param initialQuantity optional starting quantity when enabling: {@code null} or 0 → no movement (stock 0);
 *                        &gt; 0 → one STOCK_ENTRY in {@code storeId}; negative, or &gt; 0 on a disable → refused
 * @param storeId         store of the starting quantity (required when it is &gt; 0)
 * @param movementId      client-generated id of the STOCK_ENTRY (offline device), {@code null} → server generates
 * @param actorRole       "OWNER" required
 */
public record StockTrackingChange(
        UUID productId,
        boolean tracked,
        Integer initialQuantity,
        UUID storeId,
        UUID movementId,
        UUID actorId,
        String actorRole
) {}
