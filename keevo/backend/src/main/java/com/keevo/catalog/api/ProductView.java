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
        Instant updatedAt
) {}
