package com.keevo.inventory.api;

import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;

import java.util.UUID;

/**
 * A stock movement requested by another module (Story 12.2b).
 *
 * @param variantId      null for a simple product
 * @param quantityChange signed delta (positive = in, negative = out)
 * @param movementId     client-generated movement id (offline device) — null → server generates
 * @param allowNegative  true only for an offline sale that already happened: the stock may go
 *                       negative instead of failing with {@code INSUFFICIENT_STOCK}
 */
public record StockMovementInput(
        UUID productId,
        UUID variantId,
        UUID storeId,
        StockMovementKind kind,
        int quantityChange,
        UUID actorId,
        String notes,
        UUID movementId,
        boolean allowNegative
) {
    public StockMovementInput {
        if (kind == null) {
            throw new DomainException(ErrorCode.VALIDATION_ERROR, "Stock movement kind is required");
        }
    }

    /** Online movement: server-generated id, negative stock refused. */
    public StockMovementInput(UUID productId, UUID variantId, UUID storeId, StockMovementKind kind,
                              int quantityChange, UUID actorId, String notes) {
        this(productId, variantId, storeId, kind, quantityChange, actorId, notes, null, false);
    }
}
