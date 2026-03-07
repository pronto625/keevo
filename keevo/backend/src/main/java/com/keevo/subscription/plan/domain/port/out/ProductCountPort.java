package com.keevo.subscription.plan.domain.port.out;

/**
 * ProductCountPort — Output port: count active products in the current tenant schema.
 *
 * <p>Temporary count-only port. Epic 2 will introduce a full ProductRepository
 * — this port will be superseded at that point.
 */
public interface ProductCountPort {
    int countActiveProducts();
}
