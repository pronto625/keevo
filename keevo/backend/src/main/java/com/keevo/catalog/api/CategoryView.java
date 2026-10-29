package com.keevo.catalog.api;

import java.time.Instant;
import java.util.UUID;

/** Read model of a product category published by the catalog API. */
public record CategoryView(
        UUID id,
        String name,
        UUID parentId,
        boolean isActive,
        boolean isCustom,
        Instant createdAt,
        Instant updatedAt
) {}
