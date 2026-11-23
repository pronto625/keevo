package com.keevo.identity.company.application.service;

import com.keevo.identity.company.application.service.CompanyProfileService.FullProfile;
import com.keevo.identity.company.domain.model.CompanyCategory;
import com.keevo.identity.onboarding.domain.port.in.SetupSectorOnboardingUseCase;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Story 22.19 (D2) — the end of the app's sign-up steps ({@code POST /account/company/setup/complete}): marks them
 * finished and runs the sector onboarding once (sector from the company category: template categories, preferences,
 * module activation rules), in one transaction. Idempotent: a second call changes nothing.
 */
@Service
public class CompanySetupService {

    private final CompanyProfileService profiles;
    private final SetupSectorOnboardingUseCase onboarding;

    public CompanySetupService(CompanyProfileService profiles, SetupSectorOnboardingUseCase onboarding) {
        this.profiles = profiles;
        this.onboarding = onboarding;
    }

    @Transactional
    public FullProfile complete(String tenantSchema, UUID actorId) {
        FullProfile profile = profiles.completeSetup(tenantSchema);
        onboarding.onboardIfAbsent(CompanyCategory.sector(profile.category()), profile.displayName(), actorId);
        return profile;
    }
}
