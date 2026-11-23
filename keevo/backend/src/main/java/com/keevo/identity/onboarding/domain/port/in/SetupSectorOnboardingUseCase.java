package com.keevo.identity.onboarding.domain.port.in;

import com.keevo.identity.onboarding.domain.model.SectorType;

import java.util.UUID;

/**
 * Story 22.19 (D2) — the sector onboarding run by the end of the app's sign-up steps
 * ({@code POST /account/company/setup/complete}): same core as {@link CompleteOnboardingUseCase} (sector template,
 * {@code TenantOnboardedEvent}, preferences row, {@code OnboardingCompletedEvent}) without renaming the store (the
 * steps already named it). Runs once: a no-op when the tenant already has its preferences.
 */
public interface SetupSectorOnboardingUseCase {

    /**
     * @param sectorType  the sector derived from the company category ({@code null} = {@link SectorType#OTHER})
     * @param companyName the company name, for the audit line
     * @param actorId     the owner finishing the steps
     * @return true when the onboarding ran, false when the preferences already existed
     */
    boolean onboardIfAbsent(SectorType sectorType, String companyName, UUID actorId);
}
