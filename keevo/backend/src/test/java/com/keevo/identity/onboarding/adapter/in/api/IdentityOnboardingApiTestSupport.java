package com.keevo.identity.onboarding.adapter.in.api;

import com.keevo.identity.api.TenantPreferencesQueryPort;
import com.keevo.identity.api.TenantPreferencesView;
import com.keevo.identity.onboarding.domain.model.TenantPreferences;
import com.keevo.identity.onboarding.domain.port.out.TenantPreferencesRepository;

/**
 * Test-only access to the package-private preferences adapter of {@code identity :: api}
 * (Story 12.2d), for tests of other packages that drive it over a mocked repository.
 */
public final class IdentityOnboardingApiTestSupport {

    private IdentityOnboardingApiTestSupport() {}

    public static TenantPreferencesQueryPort preferencesQueryPort(TenantPreferencesRepository repository) {
        return new TenantPreferencesQueryAdapter(repository);
    }

    public static TenantPreferencesView view(TenantPreferences preferences) {
        return TenantPreferencesQueryAdapter.view(preferences);
    }
}
