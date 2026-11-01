package com.keevo.subscription.api;

/**
 * Port <em>required</em> by subscription and implemented by the module that owns products
 * (Story 12.2d — catalog): counts the tenant's ACTIVE products, for the product plan limit and
 * the subscription usage screen.
 *
 * <p>Declared here so that subscription never depends on catalog; product-creating modules read the
 * count through it as well before checking {@link com.keevo.shared.capability.entitlement.PlanLimitCheck#checkProductLimit}.
 */
public interface ProductCountPort {

    /**
     * Active products of the tenant. Story 16.8: fails closed — throws when the count cannot be read (invalid
     * schema, missing table, database fault); never answers 0 on a fault.
     */
    int countActiveProducts(String tenantSchema);
}
