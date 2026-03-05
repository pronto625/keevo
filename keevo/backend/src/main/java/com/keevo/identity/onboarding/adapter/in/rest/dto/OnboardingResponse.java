package com.keevo.identity.onboarding.adapter.in.rest.dto;

/**
 * OnboardingResponse — REST DTO for the POST /api/v1/onboarding/complete response.
 *
 * <p>Serialized as JSON: { "tenantId", "sectorType", "storeName", "categoriesCreated" }
 */
public record OnboardingResponse(
        String tenantId,
        String sectorType,
        String storeName,
        int categoriesCreated
) {}
