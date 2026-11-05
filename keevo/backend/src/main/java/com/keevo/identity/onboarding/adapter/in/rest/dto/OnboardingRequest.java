package com.keevo.identity.onboarding.adapter.in.rest.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * OnboardingRequest — REST DTO for POST /api/v1/onboarding/complete.
 *
 * <p>Validation enforced at the REST layer via Jakarta Bean Validation.
 * sectorType is a String that will be parsed to SectorType enum in the controller.
 * Story 19.13: optional {@code defaultSaleFlow} ({@code POS} | {@code DOCUMENT}; absent → POS).
 */
public record OnboardingRequest(
        @NotNull(message = "sectorType est obligatoire")
        String sectorType,

        @NotBlank(message = "Le nom de la boutique est obligatoire")
        @Size(min = 2, max = 100, message = "Le nom doit contenir entre 2 et 100 caractères")
        String storeName,

        /* Story 19.13 — optional: POS (« Caisse », default) or DOCUMENT (« Documents »); any other value → 422 */
        String defaultSaleFlow
) {

    /** Pre-19.13 arity — no sale flow (POS). */
    public OnboardingRequest(String sectorType, String storeName) {
        this(sectorType, storeName, null);
    }
}
