package com.keevo.catalog.api;

import java.util.UUID;

/**
 * Input of {@link ProductCommandPort#update}. {@code null} fields keep their current value.
 *
 * @param actorRole "OWNER" or "EMPLOYEE" — drives the draft promotion guard
 * @param trackStock Story 17.12 — {@code null} keeps the current setting; a change is OWNER-only and audited
 * @param barcode Story 20.1 — {@code null} keeps it; blank clears it
 */
public record UpdateProductInput(
        UUID productId,
        String name,
        String description,
        String sku,
        UUID categoryId,
        Integer price,
        Integer buyPrice,
        Integer transportCost,
        Integer stockQuantity,
        UUID actorId,
        String actorRole,
        Boolean trackStock,
        String barcode
) {
    /** Without the stock-tracking setting (unchanged). */
    public UpdateProductInput(UUID productId, String name, String description, String sku, UUID categoryId,
                              Integer price, Integer buyPrice, Integer transportCost, Integer stockQuantity,
                              UUID actorId, String actorRole) {
        this(productId, name, description, sku, categoryId, price, buyPrice, transportCost, stockQuantity,
                actorId, actorRole, null);
    }

    /** Without the barcode (unchanged) — Story 17.12 shape. */
    public UpdateProductInput(UUID productId, String name, String description, String sku, UUID categoryId,
                              Integer price, Integer buyPrice, Integer transportCost, Integer stockQuantity,
                              UUID actorId, String actorRole, Boolean trackStock) {
        this(productId, name, description, sku, categoryId, price, buyPrice, transportCost, stockQuantity,
                actorId, actorRole, trackStock, null);
    }
}
