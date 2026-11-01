package com.keevo.inventory.api;

import java.util.UUID;

/**
 * An already recorded stock movement, as other modules may read it (Story 17.11: a device replaying a stock entry
 * under a new operation id but the same movement id).
 *
 * @param movementType the stored movement type name ({@code STOCK_ENTRY}, {@code ADJUSTMENT}, {@code SALE}…)
 */
public record RecordedMovement(UUID id, UUID productId, UUID variantId, UUID storeId, String movementType,
                               int quantityChange) {
}
