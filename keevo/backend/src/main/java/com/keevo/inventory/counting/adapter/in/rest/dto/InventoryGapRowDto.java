package com.keevo.inventory.counting.adapter.in.rest.dto;

import com.keevo.inventory.counting.domain.model.InventoryGapRow;

import java.util.UUID;

/**
 * DTO for a single gap row in the inventory report.
 * Story 6.3 — Gap Analysis Report.
 */
public record InventoryGapRowDto(
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
    public static InventoryGapRowDto fromDomain(InventoryGapRow row) {
        return new InventoryGapRowDto(
                row.productId(), row.productName(), row.sku(), row.photoUrl(),
                row.variantId(), row.variantLabel(),
                row.theoretical(), row.physical(), row.ecart(),
                row.unitPriceXaf(), row.gapValueXaf()
        );
    }
}
