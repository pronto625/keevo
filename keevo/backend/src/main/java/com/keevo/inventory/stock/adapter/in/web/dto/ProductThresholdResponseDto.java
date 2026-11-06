package com.keevo.inventory.stock.adapter.in.web.dto;

import com.keevo.catalog.api.ProductState;
import com.keevo.catalog.api.ProductView;

import java.time.Instant;
import java.util.UUID;

/**
 * Response of {@code PATCH /api/v1/products/{productId}/threshold} (Story 2.3): the updated product.
 *
 * <p>Same fields, in the same order, as the catalog product payload — built from the catalog
 * public API (Story 12.2a) instead of the catalog web DTO, so the JSON is unchanged.
 */
public record ProductThresholdResponseDto(
        UUID id,
        String name,
        String description,
        String sku,
        UUID categoryId,
        Integer price,
        Integer buyPrice,
        Integer transportCost,
        Integer stockQuantity,
        boolean archived,
        ProductState status,
        int minimumThreshold,
        Instant createdAt,
        Instant updatedAt,
        boolean trackStock,     // Story 17.12 — same payload as the catalog product
        String barcode,         // Story 20.1 — same payload as the catalog product
        String productKey,
        boolean priceManual,
        UUID mergedIntoId
) {
    public static ProductThresholdResponseDto from(ProductView p) {
        return new ProductThresholdResponseDto(
                p.id(), p.name(), p.description(), p.sku(), p.categoryId(),
                p.price(), p.buyPrice(), p.transportCost(), p.stockQuantity(),
                p.archived(), p.status(), p.minimumThreshold(),
                p.createdAt(), p.updatedAt(), p.trackStock(),
                p.barcode(), p.productKey(), p.priceManual(), p.mergedIntoId());
    }
}
