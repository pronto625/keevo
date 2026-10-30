package com.keevo.identity.company.adapter.in.rest.dto;

import jakarta.validation.constraints.Size;

/** {@code PUT /api/v1/account/company} — blank or null clears the designation (Story 14.2). */
public record CompanyDesignationRequestDto(
        @Size(max = 150, message = "La désignation ne doit pas dépasser 150 caractères") String designation) {
}
