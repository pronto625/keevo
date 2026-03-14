package com.keevo.catalog.stock.adapter.in.web.dto;

import com.keevo.catalog.stock.domain.model.StoreProductStockEntry;

import java.util.UUID;

/**
 * REST response for a product's stock entry in a specific store.
 * Story 3.2.
 */
public record StoreProductStockEntryDto(
    UUID productId,
    String productName,
    UUID variantId,
    String variantLabel,
    UUID storeId,
    int quantity,
    int minimumThreshold,
    boolean isLow,
    boolean isCritical,
    String status  // "NORMAL" | "BAS" | "CRITIQUE"
) {
    public static StoreProductStockEntryDto from(StoreProductStockEntry e) {
        return new StoreProductStockEntryDto(
            e.productId(), e.productName(), e.variantId(), e.variantLabel(),
            e.storeId(), e.quantity(), e.minimumThreshold(),
            e.isLow(), e.isCritical(), e.status().name()
        );
    }
}
