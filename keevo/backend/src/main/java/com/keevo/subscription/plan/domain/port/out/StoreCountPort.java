package com.keevo.subscription.plan.domain.port.out;

/**
 * StoreCountPort — Output port: count active stores in the current tenant schema.
 *
 * <p>Temporary count-only port. Epic 2/3 will introduce a full StoreRepository
 * with CRUD operations — these ports will be superseded at that point.
 */
public interface StoreCountPort {
    int countActiveStores();
}
