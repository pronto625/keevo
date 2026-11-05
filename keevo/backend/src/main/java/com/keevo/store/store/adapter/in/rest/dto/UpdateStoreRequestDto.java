package com.keevo.store.store.adapter.in.rest.dto;

import com.keevo.store.api.StoreProfilePatch;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * UpdateStoreRequestDto — Request body for PATCH /api/v1/stores/{storeId}.
 * Note: type is NOT included — store type is immutable after creation.
 * Story 3.1 — AC4. Story 19.12 — optional Bref fields: absent / null ⇒ unchanged (a V1 body keeps them), empty ⇒
 * cleared (except the country, which can never be cleared).
 */
public record UpdateStoreRequestDto(
        @NotBlank(message = "Le nom de la boutique est obligatoire")
        @Size(min = 2, max = 100, message = "Le nom doit contenir entre 2 et 100 caractères")
        String name,
        String address,     // optional — null clears it (unchanged since 3.1)
        String phone,       // optional — null clears it (unchanged since 3.1)
        String category,
        String description,
        String niu,
        String rccm,
        String country
) {
    /** Pre-19.12 arity. */
    public UpdateStoreRequestDto(String name, String address, String phone) {
        this(name, address, phone, null, null, null, null, null);
    }

    public StoreProfilePatch profile() {
        return new StoreProfilePatch(category, description, niu, rccm, country);
    }
}
