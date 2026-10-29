package com.keevo.crm.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Read model of a supplier published by the crm API, with the ids of the products it supplies.
 * Field order matches the historical supplier REST payload.
 */
public record SupplierView(
        UUID id,
        String name,
        String phone,
        String email,
        boolean archived,
        List<UUID> productIds,
        Instant createdAt,
        Instant updatedAt
) {}
