package com.keevo.catalog.api;

import java.util.UUID;

/**
 * Input of {@link ProductCommandPort#update}. {@code null} fields keep their current value.
 *
 * @param actorRole "OWNER" or "EMPLOYEE" — drives the draft promotion guard
 * @param trackStock Story 17.12 — {@code null} keeps the current setting; a change is OWNER-only and audited
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
        Boolean trackStock
) {
    /** Without the stock-tracking setting (unchanged). */
    public UpdateProductInput(UUID productId, String name, String description, String sku, UUID categoryId,
                              Integer price, Integer buyPrice, Integer transportCost, Integer stockQuantity,
                              UUID actorId, String actorRole) {
        this(productId, name, description, sku, categoryId, price, buyPrice, transportCost, stockQuantity,
                actorId, actorRole, null);
    }
}
