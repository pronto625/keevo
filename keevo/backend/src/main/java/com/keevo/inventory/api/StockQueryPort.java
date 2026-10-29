package com.keevo.inventory.api;

import java.util.UUID;

/** Read access to stock levels for other modules (Story 12.2b); joins the caller's transaction. */
public interface StockQueryPort {

    /**
     * Current quantity of a product in a store, 0 when no stock level exists yet.
     *
     * @param variantId null → the simple-product (no variant) level
     */
    int availableQuantity(String tenantSchema, UUID productId, UUID variantId, UUID storeId);
}
