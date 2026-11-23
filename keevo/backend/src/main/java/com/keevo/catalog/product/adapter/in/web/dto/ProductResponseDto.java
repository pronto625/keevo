package com.keevo.catalog.product.adapter.in.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.keevo.catalog.product.domain.entity.Product;
import com.keevo.catalog.product.domain.entity.ProductStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * Response DTO for Product API endpoints
 * 
 * Includes all product information for frontend consumption
 */
public record ProductResponseDto(
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
        ProductStatus status,
        int minimumThreshold,   // Story 2.3
        Instant createdAt,
        Instant updatedAt,
        boolean trackStock,     // Story 17.12 — false: sold without stock
        String barcode,         // Story 20.1 — free reference or barcode, null = none
        String productKey,      // Story 20.1 — deduplication key (bc:… / nm:…)
        boolean priceManual,    // Story 20.1 — price typed by a person
        UUID mergedIntoId,      // Story 20.1 — merge target, null when not merged
        // Story 17.13 — only on a creation that restored the archived product of that name; absent otherwise
        @JsonInclude(JsonInclude.Include.NON_NULL) Boolean restored,
        // Story 22.19 (D20) — whether GET /products/{id}/photo has something to answer (absent where not computed)
        @JsonInclude(JsonInclude.Include.NON_NULL) Boolean hasPhoto,
        // Story 22.19 (D19) — the stock in the store asked by ?storeId= (0 without a level; absent otherwise or for an
        // untracked product)
        @JsonInclude(JsonInclude.Include.NON_NULL) Integer storeStockQuantity
) {
    /**
     * Story 16.13 (reprise, D2) — the same payload for a reader who may not see purchase prices
     * ({@code PurchasePriceVisibility}): {@code buyPrice} and {@code transportCost} are {@code null} (additive for V1
     * 2.0.1, whose DTO reads a null / missing price as 0); every other field unchanged.
     */
    public ProductResponseDto withoutPurchasePrices() {
        return new ProductResponseDto(id, name, description, sku, categoryId, price, null, null, stockQuantity,
                archived, status, minimumThreshold, createdAt, updatedAt, trackStock, barcode, productKey,
                priceManual, mergedIntoId, restored, hasPhoto, storeStockQuantity);
    }

    /** Story 22.19 (D20) — with the photo flag. */
    public ProductResponseDto withHasPhoto(boolean photo) {
        return new ProductResponseDto(id, name, description, sku, categoryId, price, buyPrice, transportCost,
                stockQuantity, archived, status, minimumThreshold, createdAt, updatedAt, trackStock, barcode,
                productKey, priceManual, mergedIntoId, restored, photo, storeStockQuantity);
    }

    /** Story 22.19 (D19) — with the stock of the store asked (ignored for an untracked product). */
    public ProductResponseDto withStoreStock(Integer quantity) {
        return new ProductResponseDto(id, name, description, sku, categoryId, price, buyPrice, transportCost,
                stockQuantity, archived, status, minimumThreshold, createdAt, updatedAt, trackStock, barcode,
                productKey, priceManual, mergedIntoId, restored, hasPhoto, trackStock ? quantity : null);
    }

    /** {@link #withoutPurchasePrices()} unless {@code purchasePriceVisible}. */
    public ProductResponseDto forReader(boolean purchasePriceVisible) {
        return purchasePriceVisible ? this : withoutPurchasePrices();
    }

    /**
     * Factory method to create DTO from domain entity
     */
    public static ProductResponseDto fromDomain(Product product) {
        return fromDomain(product, null);
    }

    /** Story 17.13 — {@code restored = true} when the creation restored an archived product. */
    public static ProductResponseDto fromDomain(Product product, Boolean restored) {
        return new ProductResponseDto(
                product.getId(),
                product.getName(),
                product.getDescription(),
                product.getSku(),
                product.getCategoryId(),
                product.getPriceValue(),
                product.getBuyPriceValue(),
                product.getTransportCostValue(),
                product.getStockQuantity(),
                product.getArchived(),
                product.getStatus(),
                product.getMinimumThreshold(),
                product.getCreatedAt(),
                product.getUpdatedAt(),
                product.isTrackStock(),
                product.getBarcode(),
                product.getProductKey(),
                product.isPriceManual(),
                product.getMergedIntoId(),
                restored,
                null,
                null
        );
    }
}