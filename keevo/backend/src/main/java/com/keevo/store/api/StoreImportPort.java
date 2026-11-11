package com.keevo.store.api;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Story 24.2 — store writes of the Bref migration tool: shops imported with their Bref id and dates, without the plan
 * limit (the plan is imported by 24.5), without event, idempotently; and their logos through the logo use case
 * (size check, re-encoding, storage, audit). The tenant context must be bound to {@code tenantSchema} (the caller opens
 * a transaction in that schema); both methods join the caller's transaction.
 */
public interface StoreImportPort {

    /**
     * Inserts the stores whose id is absent, in one JDBC batch ({@code ON CONFLICT (id) DO NOTHING}); names and Bref
     * fields are normalized by the store rules first (the caller validated them: an invalid value throws
     * {@code VALIDATION_ERROR} before any insert).
     *
     * @return the ids inserted by this call
     */
    Set<UUID> importStores(String tenantSchema, List<ImportedStore> stores);

    /**
     * Stores the logo of an imported store ({@code StoreLogoUseCase.upload}: {@code STORE_LOGO_TOO_LARGE},
     * {@code STORE_LOGO_INVALID}, {@code STORE_NOT_FOUND}).
     */
    void importLogo(String tenantSchema, UUID storeId, byte[] image, UUID actorId);

    /**
     * A shop to import.
     *
     * @param address   nullable, ≤ 255 characters
     * @param phone     nullable, E.164
     * @param category  a {@link StoreCategory} key, nullable
     * @param country   ISO 3166-1 alpha-2, nullable = {@link StoreCountry#DEFAULT}
     * @param active    {@code false} for a shop closed or deleted in Bref
     */
    record ImportedStore(UUID id, String name, String address, String phone, String category, String description,
                         String niu, String rccm, String country, boolean active, Instant createdAt,
                         Instant updatedAt) {

        public ImportedStore {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(createdAt, "createdAt");
            Objects.requireNonNull(updatedAt, "updatedAt");
        }
    }
}
