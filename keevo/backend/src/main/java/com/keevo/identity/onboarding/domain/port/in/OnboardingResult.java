package com.keevo.identity.onboarding.domain.port.in;

import com.keevo.identity.onboarding.domain.model.SectorType;

/**
 * OnboardingResult — Output of the CompleteOnboardingUseCase.
 *
 * <p>Pure Java record — immutable, contains tenant info and category count.
 */
public record OnboardingResult(
        String tenantId,
        SectorType sectorType,
        String storeName,
        int categoriesCreated
) {}
