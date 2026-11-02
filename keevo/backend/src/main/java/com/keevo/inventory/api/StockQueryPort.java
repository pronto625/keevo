package com.keevo.inventory.api;

import java.util.Optional;
import java.util.UUID;

/** Read access to stock levels for other modules (Story 12.2b); joins the caller's transaction. */
public interface StockQueryPort {

    /**
     * Current quantity of a product in a store, 0 when no stock level exists yet.
     *
     * @param variantId null → the simple-product (no variant) level
     */
    int availableQuantity(String tenantSchema, UUID productId, UUID variantId, UUID storeId);

    /**
     * Story 17.12: whether the product's stock is ignored — its setting {@code trackStock} is off, or the
     * {@code inventory} module is not enabled for the tenant. Such a product is sold without availability check
     * and without SALE movement. An unknown product answers {@code false}.
     */
    boolean isUntracked(String tenantSchema, UUID productId);

    /** Story 17.11: the movement with this id, empty when none was recorded. */
    Optional<RecordedMovement> findMovement(String tenantSchema, UUID movementId);
}
