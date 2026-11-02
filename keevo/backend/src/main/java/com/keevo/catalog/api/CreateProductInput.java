package com.keevo.catalog.api;

import java.util.UUID;

/**
 * Input of {@link ProductCommandPort#create}.
 *
 * @param id client-generated id (offline sync) — {@code null} lets the server generate one
 * @param trackStock Story 17.12 — {@code null} (V1 clients never send it) → tracked
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
        Boolean trackStock
) {
    /** Without the stock-tracking setting (tracked). */
    public CreateProductInput(UUID id, String name, String description, String sku, UUID categoryId,
                              Integer price, Integer buyPrice, Integer transportCost, Integer stockQuantity,
                              UUID actorId) {
        this(id, name, description, sku, categoryId, price, buyPrice, transportCost, stockQuantity, actorId, null);
    }
}
