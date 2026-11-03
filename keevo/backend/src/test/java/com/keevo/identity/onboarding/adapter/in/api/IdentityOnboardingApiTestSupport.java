package com.keevo.identity.onboarding.adapter.in.api;

import com.keevo.identity.api.TenantPreferencesQueryPort;
import com.keevo.identity.api.TenantPreferencesView;
import com.keevo.identity.onboarding.application.service.EffectiveTenantPreferencesService;
import com.keevo.identity.onboarding.domain.model.TenantPreferences;
import com.keevo.identity.onboarding.domain.port.out.TenantPreferencesRepository;
import com.keevo.shared.module.EntitlementService;
import com.keevo.shared.module.ModuleState;

import java.util.Set;

/**
 * Test-only access to the package-private preferences adapter of {@code identity :: api} (Story 12.2d), for tests of
 * other packages that drive it over a mocked repository; the Story 17.8 effective-preferences rule is the real
 * {@link EffectiveTenantPreferencesService} over a fixed {@code report-config} state.
 */
public final class IdentityOnboardingApiTestSupport {

    private IdentityOnboardingApiTestSupport() {}

    /** The adapter with {@code report-config} ENABLED: the stored preferences are returned as is. */
    public static TenantPreferencesQueryPort preferencesQueryPort(TenantPreferencesRepository repository) {
        return preferencesQueryPort(repository, ModuleState.ENABLED);
    }

    /** The adapter with {@code report-config} in the given state (Story 17.8: DISABLED → fixed defaults). */
    public static TenantPreferencesQueryPort preferencesQueryPort(TenantPreferencesRepository repository,
                                                                  ModuleState reportConfig) {
        return preferencesQueryPort(repository, fixedState(reportConfig));
    }

    /** The adapter over the given entitlements (e.g. the real service over a real registry). */
    public static TenantPreferencesQueryPort preferencesQueryPort(TenantPreferencesRepository repository,
                                                                  EntitlementService entitlements) {
        return new TenantPreferencesQueryAdapter(repository, new EffectiveTenantPreferencesService(entitlements));
    }

    public static TenantPreferencesView view(TenantPreferences preferences) {
        return preferences.toView();
    }

    private static EntitlementService fixedState(ModuleState state) {
        return new EntitlementService() {
            @Override public ModuleState state(String tenantSchema, String moduleId) { return state; }
            @Override public boolean isEnabled(String tenantSchema, String moduleId) { return state.readable(); }
            @Override public boolean isReadOnly(String tenantSchema, String moduleId) {
                return state == ModuleState.READ_ONLY;
            }
            @Override public void assertEnabled(String tenantSchema, String moduleId) {}
            @Override public void assertWritable(String tenantSchema, String moduleId) {}
            @Override public Set<String> enabledModules(String tenantSchema) { return Set.of("core"); }
        };
    }
}
