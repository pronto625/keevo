package com.keevo.identity.api;

/**
 * PlanType — Subscription plan types with their limits.
 *
 * <p>Pure Java enum — NO framework imports.
 */
public enum PlanType {

    /** Free plan — 1 store, 500 products, 2 employees (validated, journal I14). Since Story 16.8 these constants are
     *  only the fallback used when {@code public.plan_profiles} cannot be read: the limits live in the database. */
    FREE(1, 500, 2),
    /** 6-month Premium Trial — unlimited limits; auto-downgrades to FREE on expiry (Story 1.6) */
    PREMIUM_TRIAL(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE),
    /** Paid Premium plan — unlimited limits; activated after payment (Story 1.6+) */
    PREMIUM(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE);

    private final int maxStores;
    private final int maxProducts;
    private final int maxEmployees;

    PlanType(int maxStores, int maxProducts, int maxEmployees) {
        this.maxStores = maxStores;
        this.maxProducts = maxProducts;
        this.maxEmployees = maxEmployees;
    }

    public int getMaxStores()     { return maxStores; }
    public int getMaxProducts()   { return maxProducts; }
    public int getMaxEmployees()  { return maxEmployees; }
}
