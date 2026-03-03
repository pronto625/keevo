package com.keevo.identity.auth.domain.model;

/**
 * PlanType — Subscription plan types with their limits.
 *
 * <p>Pure Java enum — NO framework imports.
 */
public enum PlanType {

    FREE(3, 500, 5),
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
