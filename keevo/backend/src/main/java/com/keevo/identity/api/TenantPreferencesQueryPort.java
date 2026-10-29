package com.keevo.identity.api;

import java.util.Optional;

/** Read access to a tenant's preferences for other modules (Story 12.2d). */
public interface TenantPreferencesQueryPort {

    /** The tenant's preferences; empty before onboarding completed. */
    Optional<TenantPreferencesView> find(String tenantSchema);
}
