package com.keevo.store.store.adapter.in.rest.dto;

import com.keevo.store.api.StoreProfilePatch;
import com.keevo.store.api.StoreType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * CreateStoreRequestDto — Request body for POST /api/v1/stores.
 * Story 3.1 — AC1. Story 19.12 — optional Bref fields (validated by the store rules: omitted ⇒ null, country CM).
 */
public record CreateStoreRequestDto(
        @NotBlank(message = "Le nom de la boutique est obligatoire")
        @Size(min = 2, max = 100, message = "Le nom doit contenir entre 2 et 100 caractères")
        String name,
        StoreType type,     // nullable — defaults to STORE in CreateStoreService
        String address,     // optional
        String phone,       // optional
        String category,    // optional — one of the 19 Bref keys
        String description, // optional
        String niu,         // optional
        String rccm,        // optional
        String country      // optional — ISO alpha-2, default CM
) {
    /** Pre-19.12 arity. */
    public CreateStoreRequestDto(String name, StoreType type, String address, String phone) {
        this(name, type, address, phone, null, null, null, null, null);
    }

    public StoreProfilePatch profile() {
        return new StoreProfilePatch(category, description, niu, rccm, country);
    }
}
