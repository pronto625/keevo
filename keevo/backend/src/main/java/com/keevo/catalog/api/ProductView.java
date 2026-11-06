package com.keevo.catalog.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Read model of a product published by the catalog API.
 *
 * <p>Money amounts are plain integer values (XAF). Field order matches the historical product
 * REST payload so consumers mapping it field-by-field keep identical JSON.
 */
public record ProductView(
        UUID id,
        String name,
        String description,
        String sku,
        UUID categoryId,
        int price,
        int buyPrice,
        int transportCost,
        Integer stockQuantity,
        boolean archived,
        ProductState status,
        int minimumThreshold,
        Instant createdAt,
        Instant updatedAt,
        boolean trackStock,
        String barcode,      // Story 20.1 — free reference or barcode, null = none
        String productKey,   // Story 20.1 — bc:<barcode> / nm:<normalized name>
        boolean priceManual, // Story 20.1 — price typed by a person
        UUID mergedIntoId    // Story 20.1 — merge target, null when not merged
) {
    /** Story 17.12 shape (no Story 20.1 fields: no barcode, manual price, not merged). */
    public ProductView(UUID id, String name, String description, String sku, UUID categoryId,
                       int price, int buyPrice, int transportCost, Integer stockQuantity, boolean archived,
                       ProductState status, int minimumThreshold, Instant createdAt, Instant updatedAt,
                       boolean trackStock) {
        this(id, name, description, sku, categoryId, price, buyPrice, transportCost, stockQuantity, archived,
                status, minimumThreshold, createdAt, updatedAt, trackStock, null, null, true, null);
    }

    /**
     * Story 17.12 — the catalogue setting only: {@code false} = sold without stock. The effective rule also
     * depends on the {@code inventory} module (off → every product behaves as untracked), see
     * {@code inventory.api.StockQueryPort#isUntracked}.
     */
    public ProductView(UUID id, String name, String description, String sku, UUID categoryId,
                       int price, int buyPrice, int transportCost, Integer stockQuantity, boolean archived,
                       ProductState status, int minimumThreshold, Instant createdAt, Instant updatedAt) {
        this(id, name, description, sku, categoryId, price, buyPrice, transportCost, stockQuantity, archived,
                status, minimumThreshold, createdAt, updatedAt, true);
    }
}
