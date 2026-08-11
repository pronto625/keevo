package com.keevo.catalog.stock.domain.port.in;

import java.util.UUID;

/**
 * TransferStockCommand — input value object for the transfer use case.
 * GoF: Command pattern. Story 3.3.
 */
public record TransferStockCommand(
    UUID sourceStoreId,
    UUID destinationStoreId,
    UUID productId,
    UUID variantId,
    int  quantity,
    UUID actorId,
    String notes,
    UUID clientId  // Offline-first fix: client-generated id (sync push) — null → server generates.
                   // Mirrors CreateProductDto.clientId(): preserves the local transfer id created
                   // offline so the pull-merge upserts the same row instead of inserting a
                   // duplicate.
) {
    /** Backward-compatible constructor without clientId — used by the online REST path. */
    public TransferStockCommand(
            UUID sourceStoreId, UUID destinationStoreId, UUID productId,
            UUID variantId, int quantity, UUID actorId, String notes) {
        this(sourceStoreId, destinationStoreId, productId, variantId, quantity, actorId, notes, null);
    }
}
