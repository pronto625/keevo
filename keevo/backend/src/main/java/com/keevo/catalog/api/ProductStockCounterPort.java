package com.keevo.catalog.api;

import java.util.UUID;

/**
 * Story 22.19 (D11) — keeps the legacy counter {@code products.stock_quantity} (still read by apps and lists) equal to
 * the real stock: the sum of the product's store levels. Called by the module that owns stock (inventory) after every
 * stock operation, in its transaction; the dependency stays one-way ({@code inventory → catalog}).
 */
public interface ProductStockCounterPort {

    /**
     * Sets the counter of a tracked product to {@code total} (one conditional UPDATE; {@code updated_at} untouched, so
     * no later offline edit of the product is taken for outdated); an untracked product or an unchanged value is left
     * as is.
     */
    void syncStockQuantity(String tenantSchema, UUID productId, int total);
}
