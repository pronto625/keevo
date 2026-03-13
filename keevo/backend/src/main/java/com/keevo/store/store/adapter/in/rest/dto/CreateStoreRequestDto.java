package com.keevo.store.store.adapter.in.rest.dto;

import com.keevo.store.store.domain.model.StoreType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * CreateStoreRequestDto — Request body for POST /api/v1/stores.
 * Story 3.1 — AC1.
 */
public record CreateStoreRequestDto(
        @NotBlank(message = "Le nom de la boutique est obligatoire")
        @Size(min = 2, max = 100, message = "Le nom doit contenir entre 2 et 100 caractères")
        String name,
        StoreType type,     // nullable — defaults to STORE in CreateStoreService
        String address,     // optional
        String phone        // optional
) {}
