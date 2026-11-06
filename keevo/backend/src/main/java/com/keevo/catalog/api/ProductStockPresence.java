package com.keevo.catalog.api;

import java.util.UUID;

/**
 * Story 20.1 — port <em>required</em> by the catalogue's product merge and implemented by the module that owns stock
 * (inventory, optional: the catalogue starts without it). Declared here so the catalogue keeps no dependency on the
 * inventory module (same one-way rule as {@link InitialStockPort} / {@link StockTrackingGuard}).
 */
public interface ProductStockPresence {

    /** What keeps a product from being merged away. */
    enum MergeBlocker {
        /** Nothing: the product may be merged. */
        NONE,
        /** A non-zero stock level in some store (its quantity would be lost). */
        STOCK,
        /** A transfer of the product is in transit (it could never be received). */
        TRANSFER_IN_TRANSIT
    }

    /**
     * Locks the product's stock levels ({@code FOR UPDATE}, in the caller's transaction, so no movement can slip in
     * before the merge commits) and answers what blocks the merge, if anything.
     */
    MergeBlocker lockAndCheckMergeable(String tenantSchema, UUID productId);
}
