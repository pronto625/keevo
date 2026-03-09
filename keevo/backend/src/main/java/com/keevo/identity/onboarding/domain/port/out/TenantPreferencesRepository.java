package com.keevo.identity.onboarding.domain.port.out;

import com.keevo.identity.onboarding.domain.model.TenantPreferences;

import java.util.Optional;

/**
 * TenantPreferencesRepository — Secondary port for tenant preferences persistence.
 *
 * <p>Pure Java — NO Spring/framework imports.
 */
public interface TenantPreferencesRepository {

    /**
     * Persist tenant preferences.
     *
     * @param prefs the preferences to save
     * @return the persisted preferences
     */
    TenantPreferences save(TenantPreferences prefs);

    /**
     * Returns {@code true} if the tenant has already completed onboarding.
     * Determined by the presence of at least one row in {@code tenant_preferences}.
     */
    boolean hasOnboardingCompleted();

    /**
     * Find tenant preferences for the current tenant.
     * 
     * @return tenant preferences if found
     */
    Optional<TenantPreferences> findByCurrentTenant();
}
