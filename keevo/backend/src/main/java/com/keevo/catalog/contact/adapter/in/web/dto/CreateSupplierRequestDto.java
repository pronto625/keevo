package com.keevo.catalog.contact.adapter.in.web.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.List;
import java.util.UUID;

/**
 * Request DTO for creating a new supplier (Story 2.5).
 */
public record CreateSupplierRequestDto(
        @NotBlank(message = "Le nom du fournisseur est requis")
        String name,

        @NotBlank(message = "Le téléphone du fournisseur est requis")
        String phone,

        String email,
        List<UUID> productIds
) {}
