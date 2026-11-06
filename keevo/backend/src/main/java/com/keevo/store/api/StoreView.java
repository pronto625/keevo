package com.keevo.store.api;

import java.time.Instant;
import java.util.UUID;

/**
 * A store or warehouse as seen by other modules (Story 12.2d; Bref fields since 19.12).
 *
 * @param address     nullable
 * @param phone       nullable (E.164)
 * @param category    nullable
 * @param description nullable
 * @param niu         nullable
 * @param rccm        nullable
 * @param country     ISO 3166-1 alpha-2, never null ({@code CM} by default)
 * @param hasLogo     whether a logo is stored ({@code GET /api/v1/stores/{id}/logo})
 * @param shareClients Story 20.2 — the store shares its clients with the other sharing stores
 */
public record StoreView(
        UUID id,
        String name,
        StoreType type,
        String address,
        String phone,
        boolean active,
        Instant createdAt,
        Instant updatedAt,
        StoreCategory category,
        String description,
        String niu,
        String rccm,
        String country,
        boolean hasLogo,
        boolean shareClients
) {
    /** Pre-20.2 arity — clients not shared. */
    public StoreView(UUID id, String name, StoreType type, String address, String phone, boolean active,
                     Instant createdAt, Instant updatedAt, StoreCategory category, String description, String niu,
                     String rccm, String country, boolean hasLogo) {
        this(id, name, type, address, phone, active, createdAt, updatedAt, category, description, niu, rccm,
                country, hasLogo, false);
    }

    /** Pre-19.12 arity — no Bref field, country {@code CM}, no logo. */
    public StoreView(UUID id, String name, StoreType type, String address, String phone, boolean active,
                     Instant createdAt, Instant updatedAt) {
        this(id, name, type, address, phone, active, createdAt, updatedAt, null, null, null, null,
                StoreCountry.DEFAULT, false);
    }

    public boolean isWarehouse() {
        return type == StoreType.WAREHOUSE;
    }
}
