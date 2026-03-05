package com.keevo.identity.onboarding.domain.port.out;

/**
 * OnboardingStoreRepository — Secondary port for store name update during onboarding.
 *
 * <p>Updates the placeholder store name ("Ma Boutique") to the merchant's chosen name.
 * Uses a targeted update rather than full CRUD — the stores domain is not owned here.
 *
 * <p>Pure Java — NO Spring/framework imports.
 */
public interface OnboardingStoreRepository {

    /**
     * Update the name of the tenant's first (seeded) store.
     *
     * @param newName the new store name (validated: 2–100 chars)
     */
    void updateStoreName(String newName);
}
