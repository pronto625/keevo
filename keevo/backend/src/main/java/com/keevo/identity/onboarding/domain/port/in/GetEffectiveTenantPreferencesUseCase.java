package com.keevo.identity.onboarding.domain.port.in;

import com.keevo.identity.api.TenantPreferencesView;
import com.keevo.identity.onboarding.domain.model.TenantPreferences;

/**
 * GetEffectiveTenantPreferencesUseCase — Story 17.8: the report / alert preferences that really apply to a tenant.
 * When the paid module {@value #REPORT_CONFIG} is DISABLED (Free, grace over) they are
 * {@link TenantPreferencesView#fixedDefaults()} (keeping the stored {@code leaderboardVisibleToEmployees}, Story
 * 17.2); otherwise the stored values. Editable only when ENABLED. An unreadable module state → stored values, not
 * editable (WARN logged). The stored row is never modified.
 *
 * <p>Pure Java — NO Spring/framework imports.
 */
public interface GetEffectiveTenantPreferencesUseCase {

    /** The paid module that makes the report / alert preferences configurable. */
    String REPORT_CONFIG = "report-config";

    /** The effective preferences of a stored row and whether the tenant may change them, from one state read. */
    record EffectivePreferences(TenantPreferencesView view, boolean reportConfigEditable) {}

    /**
     * @param tenantSchema the tenant the stored row belongs to
     * @param stored       the tenant's stored preferences row
     */
    EffectivePreferences effective(String tenantSchema, TenantPreferences stored);
}
