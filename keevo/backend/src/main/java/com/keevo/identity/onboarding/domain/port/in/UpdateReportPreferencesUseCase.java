package com.keevo.identity.onboarding.domain.port.in;

import com.keevo.identity.onboarding.domain.model.TenantPreferences;

/**
 * UpdateReportPreferencesUseCase — Port-in for updating report preferences.
 * Story 7.5 — Task 2.5
 *
 * <p>Pure Java — NO Spring/framework imports.
 */
public interface UpdateReportPreferencesUseCase {

    /**
     * Update report preferences for the current tenant.
     *
     * @param command the command with all updated preference fields
     * @return the saved preferences
     * @throws com.keevo.shared.domain.exception.DomainException PREFERENCES_NOT_FOUND if no preferences exist
     */
    TenantPreferences update(UpdateReportPreferencesCommand command);
}
