package com.keevo.store.api;

import java.time.Instant;
import java.util.UUID;

/**
 * A store or warehouse as seen by other modules (Story 12.2d).
 *
 * @param address nullable
 * @param phone   nullable (E.164)
 */
public record StoreView(
        UUID id,
        String name,
        StoreType type,
        String address,
        String phone,
        boolean active,
        Instant createdAt,
        Instant updatedAt
) {
    public boolean isWarehouse() {
        return type == StoreType.WAREHOUSE;
    }
}
