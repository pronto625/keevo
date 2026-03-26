package com.keevo.inventory.counting.domain.model;

import java.util.UUID;

/**
 * InventoryProductRow — Read model / projection for counting form.
 * Combines product info + theoretical stock + existing count data.
 */
public record InventoryProductRow(
    UUID productId,
    String productName,
    String sku,
    String photoUrl,
    UUID variantId,
    String variantLabel,
    int theoreticalQty,
    Integer physicalQty,    // null if not yet counted
    Integer ecart           // null if not yet counted
) {
    public static InventoryProductRow of(UUID productId, String productName, String sku,
                                         String photoUrl,
                                         UUID variantId, String variantLabel,
                                         int theoreticalQty, Integer physicalQty) {
        Integer ecart = physicalQty != null ? physicalQty - theoreticalQty : null;
        return new InventoryProductRow(productId, productName, sku, photoUrl,
                variantId, variantLabel, theoreticalQty, physicalQty, ecart);
    }
}
