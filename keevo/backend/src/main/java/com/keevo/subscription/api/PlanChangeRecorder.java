package com.keevo.subscription.api;

import com.keevo.identity.api.PlanType;
import com.keevo.subscription.api.PlanChangeCause;

import java.util.UUID;

/**
 * PlanChangeRecorder — driven port keeping the global (public schema) view of tenant plans in
 * line with the tenant-schema {@code subscriptions} row (Story 9-2).
 *
 * <p>Both methods join the caller's transaction: a plan change, its history row and the
 * {@code public.tenants.plan_type} update commit or roll back together.
 */
public interface PlanChangeRecorder {

    /** Appends a row to {@code public.tenant_plan_events}. {@code fromPlan}, {@code actorId}, {@code reason} may be null. */
    void recordEvent(UUID tenantId, PlanType fromPlan, PlanType toPlan, PlanChangeCause cause,
                     UUID actorId, String reason);

    /** Sets {@code public.tenants.plan_type} (read by the admin tenant list and its plan filter). */
    void syncPublicPlanType(UUID tenantId, PlanType plan);
}
