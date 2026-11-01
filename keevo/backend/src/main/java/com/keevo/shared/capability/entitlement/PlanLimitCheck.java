package com.keevo.shared.capability.entitlement;

import java.util.function.IntSupplier;

/**
 * Plan-limit enforcement for the modules that create stores, products or employees (Story 12.2d; core
 * capability since 12.6a, formerly {@code subscription.api.PlanLimitPort}, implemented by subscription).
 * The caller reads its current count and the plan ({@link TenantPlanQuery}) and calls the
 * check right before its guarded write, in the same transaction.
 *
 * <p>Each check throws {@code DomainException(PLAN_LIMIT_EXCEEDED)} with details
 * {@code entity}/{@code limit}/{@code current} when {@code current >= limit}, and
 * {@code DomainException(SUBSCRIPTION_NOT_FOUND)} when {@code plan} is null. Only the plan type
 * decides: {@link PlanView#active()} is not consulted (same as before 12.2d). Since Story 16.8 the limit is the one the
 * {@link PlanView} carries (built from the plan type's profile, {@link PlanProfiles}, when the plan was read).
 */
public interface PlanLimitCheck {

    void checkStoreLimit(PlanView plan, int currentStores);

    /**
     * Story 16.8 — product check that counts only when the plan's product limit is finite (an unlimited plan never
     * counts) and fails closed: a count that throws refuses the creation with {@code PRODUCT_QUOTA_UNAVAILABLE} (503).
     */
    void checkProductLimit(PlanView plan, IntSupplier currentProducts);

    /**
     * Story 16.8 — products that can still be created under the plan ({@code Integer.MAX_VALUE} when unlimited, no
     * count then); fails closed like {@link #checkProductLimit(PlanView, IntSupplier)}.
     */
    int remainingProducts(PlanView plan, IntSupplier currentProducts);

    void checkEmployeeLimit(PlanView plan, int currentEmployees);
}
