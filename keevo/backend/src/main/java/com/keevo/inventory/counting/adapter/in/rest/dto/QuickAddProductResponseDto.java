package com.keevo.inventory.counting.adapter.in.rest.dto;

import com.keevo.inventory.counting.domain.model.QuickAddProductResult;

import java.util.UUID;

/**
 * QuickAddProductResponseDto — response after a successful quick-add.
 * Story 6.2.a.
 */
public record QuickAddProductResponseDto(
    UUID productId,
    String productName,
    String sku,
    UUID categoryId,
    int physicalQty,
    UUID inventoryCountId,
    UUID stockLevelId
) {
    public static QuickAddProductResponseDto fromDomain(QuickAddProductResult result) {
        return new QuickAddProductResponseDto(
            result.product().id(),
            result.product().name(),
            result.product().sku(),
            result.product().categoryId(),
            result.inventoryCount().getPhysical(),
            result.inventoryCount().getId(),
            result.stockLevel().getId()
        );
    }
}
