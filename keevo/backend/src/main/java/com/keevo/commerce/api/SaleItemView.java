package com.keevo.commerce.api;

import java.util.UUID;

/** One line of a {@link SaleView} (Story 12.2c). {@code variantId} is null for a simple product. */
public record SaleItemView(
        UUID id,
        UUID productId,
        UUID variantId,
        String productName,
        int catalogueUnitPrice,
        int appliedUnitPrice,
        int quantity
) {
}
