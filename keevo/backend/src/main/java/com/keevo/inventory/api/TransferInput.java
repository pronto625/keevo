package com.keevo.inventory.api;

import java.util.UUID;

/**
 * A stock transfer between two stores requested by another module (Story 12.2b).
 *
 * @param variantId null for a simple product
 * @param clientId  client-generated transfer id (offline device) — null → server generates; a
 *                  replay of an existing id returns that transfer without a second movement
 */
public record TransferInput(
        UUID sourceStoreId,
        UUID destinationStoreId,
        UUID productId,
        UUID variantId,
        int quantity,
        UUID actorId,
        String notes,
        UUID clientId
) {}
