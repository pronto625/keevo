package com.keevo.catalog.api;

import java.util.UUID;

/**
 * Input of {@link ProductCommandPort#create}.
 *
 * @param id client-generated id (offline sync) — {@code null} lets the server generate one
 * @param trackStock Story 17.12 — {@code null} (V1 clients never send it) → tracked
 * @param barcode Story 20.1 — free reference or barcode, {@code null} / blank → none
 */
public record CreateProductInput(
        UUID id,
        String name,
        String description,
        String sku,
        UUID categoryId,
        Integer price,
        Integer buyPrice,
        Integer transportCost,
        Integer stockQuantity,
        UUID actorId,
        Boolean trackStock,
        String barcode
) {
    /** Without the stock-tracking setting (tracked). */
    public CreateProductInput(UUID id, String name, String description, String sku, UUID categoryId,
                              Integer price, Integer buyPrice, Integer transportCost, Integer stockQuantity,
                              UUID actorId) {
        this(id, name, description, sku, categoryId, price, buyPrice, transportCost, stockQuantity, actorId, null);
    }

    /** Without the barcode (Story 17.12 shape). */
    public CreateProductInput(UUID id, String name, String description, String sku, UUID categoryId,
                              Integer price, Integer buyPrice, Integer transportCost, Integer stockQuantity,
                              UUID actorId, Boolean trackStock) {
        this(id, name, description, sku, categoryId, price, buyPrice, transportCost, stockQuantity, actorId,
                trackStock, null);
    }
}
