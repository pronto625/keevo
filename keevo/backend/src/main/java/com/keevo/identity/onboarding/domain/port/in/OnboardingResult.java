package com.keevo.identity.onboarding.domain.port.in;

import com.keevo.identity.onboarding.domain.model.SectorType;

/**
 * OnboardingResult — Output of the CompleteOnboardingUseCase.
 *
 * <p>Pure Java record — immutable, contains tenant info and category count.
 *
 * @param categoriesCreated template categories requested (Story 12.6a: seeded by the catalog on
 *                          {@code TenantOnboardedEvent}); onboarding runs once per tenant, so on that first
 *                          onboarding it equals the rows created
 */
public record OnboardingResult(
        String tenantId,
        SectorType sectorType,
        String storeName,
        int categoriesCreated
) {}
