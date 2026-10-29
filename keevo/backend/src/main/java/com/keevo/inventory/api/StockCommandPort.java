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
     * @throws com.keevo.shared.domain.exception.DomainException PRODUCT_NOT_FOUND, or
     *         INSUFFICIENT_STOCK when the level would go negative and {@code allowNegative} is false
     */
    void recordMovement(StockMovementInput input);

    /**
     * Sets the absolute quantity of a product in a store (ADJUSTMENT movement of the delta).
     *
     * @param movementId client-generated movement id, null → server generates
     * @throws com.keevo.shared.domain.exception.DomainException VALIDATION_ERROR if negative
     */
    void adjustTo(UUID productId, UUID variantId, UUID storeId, int newQuantity,
                  UUID actorId, String notes, UUID movementId);

    /**
     * Records received goods (STOCK_ENTRY movement).
     *
     * @param movementId client-generated movement id, null → server generates
     * @throws IllegalArgumentException if {@code quantity <= 0}
     */
    void recordEntry(UUID productId, UUID variantId, UUID storeId, int quantity,
                     UUID actorId, String notes, UUID movementId);
}
