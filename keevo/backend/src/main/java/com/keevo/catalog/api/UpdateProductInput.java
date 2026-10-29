package com.keevo.catalog.api;

import java.util.UUID;

/**
 * Input of {@link ProductCommandPort#update}. {@code null} fields keep their current value.
 *
 * @param actorRole "OWNER" or "EMPLOYEE" — drives the draft promotion guard
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
        String actorRole
) {}
