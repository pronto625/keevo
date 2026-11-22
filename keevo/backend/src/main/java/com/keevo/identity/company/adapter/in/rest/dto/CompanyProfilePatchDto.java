package com.keevo.identity.company.adapter.in.rest.dto;

import com.keevo.identity.company.domain.port.out.CompanyProfileRepository.DetailsPatch;
import jakarta.validation.constraints.Size;

/**
 * {@code PATCH /api/v1/account/company/profile} — absent or null ⇒ unchanged; an empty string ⇒ cleared (the
 * country can never be cleared). {@code category} is one of the 19 Bref keys.
 */
public record CompanyProfilePatchDto(
        @Size(max = 150, message = "La désignation ne doit pas dépasser 150 caractères") String designation,
        @Size(max = 1000, message = "La description ne doit pas dépasser 1000 caractères") String description,
        @Size(max = 40) String category,
        @Size(max = 255, message = "L'adresse ne doit pas dépasser 255 caractères") String address,
        @Size(max = 30, message = "Le téléphone ne doit pas dépasser 30 caractères") String phone,
        @Size(max = 150, message = "L'e-mail ne doit pas dépasser 150 caractères") String email,
        @Size(max = 30, message = "Le NIU ne doit pas dépasser 30 caractères") String niu,
        @Size(max = 50, message = "Le RCCM ne doit pas dépasser 50 caractères") String rccm,
        @Size(max = 2) String country) {

    public DetailsPatch toPatch() {
        return new DetailsPatch(designation, description, category, address, phone, email, niu, rccm, country);
    }
}
