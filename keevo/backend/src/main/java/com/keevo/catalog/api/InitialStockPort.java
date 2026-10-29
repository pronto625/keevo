package com.keevo.catalog.api;

import java.util.UUID;

/**
 * Port <em>required</em> by the catalog and implemented by the module that owns stock
 * (Story 12.2b): records the initial stock of a product the catalog has just created (CSV import).
 *
 * <p>Declared here rather than called on the inventory's API so that the dependency stays one-way
 * ({@code inventory → catalog}): catalog never imports inventory. The call runs in the caller's
 * transaction, so a failure rolls back with the product row.
 */
public interface InitialStockPort {

    /**
     * Records {@code quantity} (> 0) units entering {@code storeId} for a simple product (no variant).
     *
     * @throws com.keevo.shared.domain.exception.DomainException PRODUCT_NOT_FOUND
     */
    void recordInitialStock(String tenantSchema, UUID productId, UUID storeId, int quantity, UUID actorId, String notes);
}
