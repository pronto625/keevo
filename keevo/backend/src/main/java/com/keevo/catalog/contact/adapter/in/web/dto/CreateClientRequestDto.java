package com.keevo.catalog.contact.adapter.in.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Request DTO for creating a new client (Story 2.5).
 */
public record CreateClientRequestDto(
        @NotBlank(message = "Le nom du client est requis")
        String name,

        @NotBlank(message = "Le téléphone du client est requis")
        String phone,

        String email,
        String notes
) {}
