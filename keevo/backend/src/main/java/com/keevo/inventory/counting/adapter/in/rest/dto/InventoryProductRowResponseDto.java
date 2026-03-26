package com.keevo.inventory.counting.adapter.in.rest.dto;

import com.keevo.inventory.counting.domain.model.InventoryProductRow;

import java.util.UUID;

public record InventoryProductRowResponseDto(
        UUID productId,
        String productName,
        String sku,
        String photoUrl,
        UUID variantId,
        String variantLabel,
        int theoreticalQty,
        Integer physicalQty,
        Integer ecart
) {
    public static InventoryProductRowResponseDto fromDomain(InventoryProductRow row) {
        return new InventoryProductRowResponseDto(
                row.productId(), row.productName(), row.sku(), row.photoUrl(),
                row.variantId(), row.variantLabel(),
                row.theoreticalQty(), row.physicalQty(), row.ecart());
    }
}
