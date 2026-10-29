package com.keevo.subscription.api;

/**
 * Plan-limit enforcement for the modules that create stores, products or employees (Story 12.2d).
 * The caller reads its current count and the plan ({@link SubscriptionQueryPort}) and calls the
 * check right before its guarded write, in the same transaction.
 *
 * <p>Each check throws {@code DomainException(PLAN_LIMIT_EXCEEDED)} with details
 * {@code entity}/{@code limit}/{@code current} when {@code current >= limit}, and
 * {@code DomainException(SUBSCRIPTION_NOT_FOUND)} when {@code plan} is null. Only the plan type
 * decides: {@link PlanView#active()} is not consulted (same as before 12.2d).
 */
public interface PlanLimitPort {

    void checkStoreLimit(PlanView plan, int currentStores);

    void checkProductLimit(PlanView plan, int currentProducts);

    void checkEmployeeLimit(PlanView plan, int currentEmployees);
}
