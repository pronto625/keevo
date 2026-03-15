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
    String notes
) {}
