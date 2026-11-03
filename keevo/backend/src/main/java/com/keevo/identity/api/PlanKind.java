package com.keevo.identity.api;

/**
 * PlanKind — what a plan <b>is</b>, whatever its code (Story 19.7a, ADR-05): the behaviours that used to depend on a
 * plan code (free limits, paid modules, trial expiry, V1 value, revenue, admin filters) depend on the kind read from
 * the plan catalogue ({@code public.plans.kind}). The only fixed list: these are concepts, not offer content.
 *
 * <p>Pure Java enum — NO framework imports.
 */
public enum PlanKind {

    /** No payment, no end: the plan a tenant falls back to. */
    FREE,
    /** A time-limited trial of the paid offer: runs to its end, then falls back to the free plan. */
    TRIAL,
    /** A paid plan: runs until its paid period ends, then falls back to the free plan. */
    PAID;

    /** Whether this kind has an end after which the expiry job downgrades to the free plan (trial or paid). */
    public boolean expires() {
        return this != FREE;
    }

    /**
     * The V1 compatibility value (Flutter 2.0.1 knows three codes only): {@code GET /subscription/me} {@code planType}
     * and {@code PLAN_LIMIT_EXCEEDED} {@code details.plan}.
     */
    public PlanType v1Plan() {
        return switch (this) {
            case FREE -> PlanType.FREE;
            case TRIAL -> PlanType.PREMIUM_TRIAL;
            case PAID -> PlanType.PREMIUM;
        };
    }
}
