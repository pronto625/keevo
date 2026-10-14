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

    /**
     * Give the company ({@code public.tenants.name}) the merchant's shop name while it still
     * carries its technical code (set at registration). Without it the code « KV-XXXXXX »
     * was shown as the company name (tenant picker, PDF headers, admin).
     *
     * @param name the shop name chosen at onboarding
     */
    void nameCompanyIfStillCode(String name);
}
