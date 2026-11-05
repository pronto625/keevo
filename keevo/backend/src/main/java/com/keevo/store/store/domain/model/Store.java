package com.keevo.store.store.domain.model;

import com.keevo.store.api.StoreCategory;
import com.keevo.store.api.StoreCountry;
import com.keevo.store.api.StoreType;

import java.time.Instant;
import java.util.UUID;

/**
 * Store — Immutable domain model for a store or warehouse.
 *
 * <p>Pure Java record — no framework dependencies.
 * Story 3.1 — Store & Warehouse management. Story 19.12 — the Bref fields ({@code category}, {@code description},
 * {@code logoPath}, {@code niu}, {@code rccm}, {@code country}).
 *
 * @param logoPath the stored logo file name ({@code <id>.png|jpg}), {@code null} = no logo — never exposed
 * @param country  ISO 3166-1 alpha-2, {@code CM} when not given
 */
public record Store(
        UUID id,
        String name,
        StoreType type,
        String address,     // nullable
        String phone,       // nullable
        boolean isActive,
        Instant createdAt,
        Instant updatedAt,
        StoreCategory category,   // nullable
        String description,       // nullable
        String logoPath,          // nullable
        String niu,               // nullable
        String rccm,              // nullable
        String country
) {
    public Store {
        if (country == null || country.isBlank()) {
            country = StoreCountry.DEFAULT;
        }
    }

    /** Pre-19.12 arity — no Bref field, country {@code CM}. Production code rebuilding a stored row uses the withers. */
    public Store(UUID id, String name, StoreType type, String address, String phone, boolean isActive,
                 Instant createdAt, Instant updatedAt) {
        this(id, name, type, address, phone, isActive, createdAt, updatedAt, null, null, null, null, null, null);
    }

    public boolean hasLogo() {
        return logoPath != null;
    }

    /** Same store, active or not. */
    public Store withActive(boolean active, Instant now) {
        return new Store(id, name, type, address, phone, active, createdAt, now, category, description, logoPath,
                niu, rccm, country);
    }

    /** Same store, name / address / phone replaced (type immutable). */
    public Store withContact(String newName, String newAddress, String newPhone, Instant now) {
        return new Store(id, newName, type, newAddress, newPhone, isActive, createdAt, now, category, description,
                logoPath, niu, rccm, country);
    }

    /** Same store, Bref fields replaced (already validated: see {@link StoreProfileRules}). */
    public Store withProfile(StoreCategory newCategory, String newDescription, String newNiu, String newRccm,
                             String newCountry) {
        return new Store(id, name, type, address, phone, isActive, createdAt, updatedAt, newCategory, newDescription,
                logoPath, newNiu, newRccm, newCountry);
    }

    /** Same store, logo file set or removed. */
    public Store withLogoPath(String newLogoPath, Instant now) {
        return new Store(id, name, type, address, phone, isActive, createdAt, now, category, description,
                newLogoPath, niu, rccm, country);
    }
}
