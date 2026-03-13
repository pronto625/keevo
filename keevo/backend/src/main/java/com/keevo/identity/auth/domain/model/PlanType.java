package com.keevo.identity.auth.domain.model;

/**
 * PlanType — Subscription plan types with their limits.
 *
 * <p>Pure Java enum — NO framework imports.
 */
public enum PlanType {

    /** Free plan — limited to 3 stores, 500 products, 3 employees (Story 3.1: maxStores updated 1→3) */
    FREE(3, 500, 3),
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
