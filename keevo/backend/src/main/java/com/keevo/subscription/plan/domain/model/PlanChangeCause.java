package com.keevo.subscription.plan.domain.model;

/**
 * PlanChangeCause — why a tenant's plan changed (Story 9-2), stored in
 * {@code public.tenant_plan_events.cause}. 9-3 derives churn and conversions from it.
 */
public enum PlanChangeCause {
    /** Super admin activated a plan (new endpoint or legacy {@code /subscriptions/{schema}/activate}). */
    ADMIN_ACTIVATION,
    /** Super admin moved the tenant back to FREE. */
    ADMIN_DOWNGRADE,
    /** Nightly job downgraded an expired PREMIUM / PREMIUM_TRIAL to FREE. */
    EXPIRY,
    /** Tenant registration (starts on PREMIUM_TRIAL). */
    REGISTRATION,
    /**
     * One-shot correction of a stale {@code public.tenants.plan_type} by {@code PlanTypeRealignJob}
     * (not a real plan change at that time: ignored by 9-3 churn and conversion).
     */
    REALIGNMENT
}
