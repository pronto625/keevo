package com.keevo.catalog.stock.domain.model;

import java.util.UUID;

/**
 * StoreProductStockEntry — Composite leaf (GoF: Composite).
 * Stock level for one product (or variant) in one store.
 * Story 3.2.
 */
public record StoreProductStockEntry(
    UUID productId,
    String productName,
    UUID variantId,         // nullable — only for variant products
    String variantLabel,    // nullable — e.g., "Taille L / Rouge"
    UUID storeId,
    int quantity,
    int minimumThreshold
) {
    /** True when stock is at or below the configured alert threshold.
     *  Returns false when threshold is 0 (no alert configured).
     *  Also returns false when quantity is 0 (critical takes priority). */
    public boolean isLow() {
        return minimumThreshold > 0 && quantity > 0 && quantity <= minimumThreshold;
    }

    /** True when stock is zero — critical regardless of threshold. */
    public boolean isCritical() {
        return quantity == 0;
    }

    /** Status label for the frontend badge. */
    public StockStatus status() {
        if (isCritical()) return StockStatus.CRITIQUE;
        if (isLow())      return StockStatus.BAS;
        return StockStatus.NORMAL;
    }
}
