package com.keevo.catalog.api;

import java.util.UUID;

/**
 * Input of {@link ProductCommandPort#create}.
 *
 * @param id client-generated id (offline sync) — {@code null} lets the server generate one
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
        UUID actorId
) {}
