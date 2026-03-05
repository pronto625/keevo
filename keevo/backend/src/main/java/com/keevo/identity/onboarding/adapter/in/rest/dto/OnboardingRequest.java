package com.keevo.identity.onboarding.adapter.in.rest.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * OnboardingRequest — REST DTO for POST /api/v1/onboarding/complete.
 *
 * <p>Validation enforced at the REST layer via Jakarta Bean Validation.
 * sectorType is a String that will be parsed to SectorType enum in the controller.
 */
public record OnboardingRequest(
        @NotNull(message = "sectorType est obligatoire")
        String sectorType,

        @NotBlank(message = "Le nom de la boutique est obligatoire")
        @Size(min = 2, max = 100, message = "Le nom doit contenir entre 2 et 100 caractères")
        String storeName
) {}
