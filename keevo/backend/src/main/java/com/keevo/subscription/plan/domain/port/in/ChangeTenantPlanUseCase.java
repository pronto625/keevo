package com.keevo.subscription.plan.domain.port.in;

import java.util.Optional;

/**
 * ChangeTenantPlanUseCase — cross-schema plan reads/changes for admin flows (Story 9-2).
 *
 * <p>Unlike {@link ActivatePlanUseCase} (JPA, own REQUIRES_NEW transaction, keyed by schema name),
 * this use case works through schema-qualified JDBC and JOINS the caller's transaction, so the
 * admin flow can write a payment, the subscription, {@code public.tenants.plan_type} and the plan
 * history in one unit of work.
 */
public interface ChangeTenantPlanUseCase {

    /** Current plan of the tenant schema, locked FOR UPDATE until the caller's transaction ends; empty if no subscription row. */
    Optional<CurrentPlan> currentPlan(String schemaName);

    /**
     * Applies {@code newPlan} (limits from {@link com.keevo.identity.auth.domain.model.PlanType},
     * status ACTIVE, {@code expiresAt}), syncs {@code public.tenants.plan_type} and appends a plan event.
     *
     * @return the plan before the change
     * @throws com.keevo.shared.domain.exception.DomainException SUBSCRIPTION_NOT_FOUND if the schema has no subscription row
     */
    CurrentPlan changePlan(ChangeTenantPlanCommand command);
}
