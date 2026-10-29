package com.keevo.subscription.api;

import java.util.Optional;

/** Read access to the tenant's subscription for other modules (Story 12.2d). */
public interface SubscriptionQueryPort {

    /**
     * The tenant's subscription row as {@code SubscriptionRepository#findActivePlan()} has always
     * returned it (unchanged by 12.2d): the <em>oldest</em> row of the tenant's {@code subscriptions}
     * table, whatever its status — in practice the single row. Empty if the tenant has none (callers
     * decide: product/store/employee creation refuse with {@code SUBSCRIPTION_NOT_FOUND}, exports
     * treat it as FREE).
     *
     * <p>{@link PlanView#active()} {@code false} (SUSPENDED / EXPIRED status) does not change the plan
     * limits: {@link PlanLimitPort} checks the plan type only; callers that care (exports' premium
     * check) test {@code active()} themselves.
     */
    Optional<PlanView> findCurrentPlan(String tenantSchema);
}
