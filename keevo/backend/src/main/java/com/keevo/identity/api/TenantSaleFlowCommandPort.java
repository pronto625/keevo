package com.keevo.identity.api;

/**
 * Sets a tenant's default sale flow from another module (Story 19.12) — the Bref importer (24.x) sets
 * {@link SaleFlow#DOCUMENT} for an imported owner. Works with or without a bound tenant context (the schema is
 * explicit and validated). Joins the caller's transaction when there is one. Audited (system actor) when the value
 * changes; the same value is a no-op.
 */
public interface TenantSaleFlowCommandPort {

    /**
     * @throws IllegalArgumentException invalid schema name, or a null flow
     * @throws IllegalStateException    the tenant has no preferences row (onboarding not done)
     */
    void set(String tenantSchema, SaleFlow flow);
}
