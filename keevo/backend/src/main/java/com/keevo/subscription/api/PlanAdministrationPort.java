package com.keevo.subscription.api;

import java.util.Optional;

/**
 * Plan changes driven by the super-admin flows (Story 12.2d, over the 9-2 use cases).
 *
 * <p>{@link #activatePlan} always runs in a new transaction (REQUIRES_NEW — legacy activation, keyed
 * by schema name; the caller's transaction, if any, is suspended). {@link #currentPlan} and
 * {@link #changePlan} work through schema-qualified JDBC and require the caller's transaction
 * (MANDATORY — no transaction → {@code IllegalTransactionStateException}), so the FOR UPDATE lock
 * lasts until it ends and an admin flow can write a payment, the subscription, the public plan
 * type and the plan history in one unit of work.
 */
public interface PlanAdministrationPort {

    void activatePlan(ActivatePlanInput input);

    /** Current plan of the tenant schema, locked FOR UPDATE until the caller's transaction ends; empty if none. */
    Optional<CurrentPlan> currentPlan(String tenantSchema);

    /**
     * Applies the new plan (limits, status ACTIVE, expiry), syncs {@code public.tenants.plan_type} and
     * appends a plan event.
     *
     * @return the plan before the change
     * @throws com.keevo.shared.domain.exception.DomainException SUBSCRIPTION_NOT_FOUND
     */
    CurrentPlan changePlan(ChangePlanInput input);
}
