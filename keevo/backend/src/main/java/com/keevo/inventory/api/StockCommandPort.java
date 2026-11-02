package com.keevo.inventory.api;

import java.util.UUID;

/**
 * Write access to stock levels for other modules (Story 12.2b). Every call records an immutable
 * movement, updates the level and publishes the usual stock events (adjusted, threshold
 * breached), exactly like the inventory's own operations. No transaction of its own beyond the
 * inventory services': inside a caller's transaction the movement commits or rolls back with it.
 */
public interface StockCommandPort {

    /**
     * Records a movement of the given kind.
     *
     * <p>Story 17.12: a product whose stock is untracked ({@link StockQueryPort#isUntracked}) is skipped
     * silently — no movement, no level, no alert.
     *
     * @throws com.keevo.shared.domain.exception.DomainException PRODUCT_NOT_FOUND, or
     *         INSUFFICIENT_STOCK when the level would go negative and {@code allowNegative} is false
     */
    void recordMovement(String tenantSchema, StockMovementInput input);

    /**
     * Sets the absolute quantity of a product in a store (ADJUSTMENT movement of the delta).
     *
     * @param movementId client-generated movement id, null → server generates
     * @throws com.keevo.shared.domain.exception.DomainException VALIDATION_ERROR if negative
     */
    void adjustTo(String tenantSchema, UUID productId, UUID variantId, UUID storeId, int newQuantity,
                  UUID actorId, String notes, UUID movementId);

    /**
     * Records received goods (STOCK_ENTRY movement).
     *
     * @param movementId client-generated movement id, null → server generates
     * @throws IllegalArgumentException if {@code quantity <= 0}
     */
    void recordEntry(String tenantSchema, UUID productId, UUID variantId, UUID storeId, int quantity,
                     UUID actorId, String notes, UUID movementId);

    /**
     * Story 17.12 — turns a product's stock tracking on or off, with an optional starting quantity on activation
     * (one STOCK_ENTRY in the given store; other stores start at 0). Disabling keeps the levels, frozen.
     * One transaction: the setting and the entry commit together. Returns whether the starting entry was recorded
     * ({@code false} when none was asked, or when it was ignored because the product was already tracked).
     *
     * @throws com.keevo.shared.domain.exception.DomainException FORBIDDEN (not the owner), PRODUCT_NOT_FOUND,
     *         STORE_NOT_FOUND, VALIDATION_ERROR (negative quantity, quantity without store, quantity on a disable)
     */
    boolean changeTracking(String tenantSchema, StockTrackingChange change);
}
