package com.keevo.store.store.adapter.in.rest.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * UpdateStoreRequestDto — Request body for PATCH /api/v1/stores/{storeId}.
 * Note: type is NOT included — store type is immutable after creation.
 * Story 3.1 — AC4.
 */
public record UpdateStoreRequestDto(
        @NotBlank(message = "Le nom de la boutique est obligatoire")
        @Size(min = 2, max = 100, message = "Le nom doit contenir entre 2 et 100 caractères")
        String name,
        String address,     // optional
        String phone        // optional
) {}
