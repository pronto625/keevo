package com.keevo.inventory.counting.domain.model;

import java.util.UUID;

/**
 * InventoryGapRow — one product's gap data in the inventory report.
 *
 * <p>Pure Java record — no framework deps.
 * Story 6.3 — Gap Analysis Report.
 */
public record InventoryGapRow(
        UUID productId,
        String productName,
        String sku,
        String photoUrl,
        UUID variantId,
        String variantLabel,
        int theoretical,
        int physical,
        int ecart,
        int unitPriceXaf,
        long gapValueXaf
) {
    public boolean isSurplus() { return ecart > 0; }
    public boolean isShortage() { return ecart < 0; }
    public boolean isConcordant() { return ecart == 0; }
}
