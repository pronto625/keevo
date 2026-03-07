package com.keevo.subscription.plan.domain.model;

/**
 * SubscriptionStatus — Lifecycle status of a tenant subscription.
 *
 * <p>Note: PREMIUM_TRIAL is a {@link com.keevo.identity.auth.domain.model.PlanType},
 * NOT a status. A PREMIUM_TRIAL tenant has {@code status=ACTIVE} + {@code planType=PREMIUM_TRIAL}
 * + {@code expiresAt} set. On expiry, planType becomes FREE (status stays ACTIVE).
 * SUSPENDED is reserved for manual Super Admin lockout only (not used in natural expiry flow).
 */
public enum SubscriptionStatus {

    /** Normal active state — tenant can use all features within plan limits. */
    ACTIVE,

    /** Manual admin lockout — all write operations blocked (GET/HEAD still work). */
    SUSPENDED,

    /** Technically expired row (planType still holds old value). Rarely used — expiry
     * scheduler downgrades planType to FREE, status stays ACTIVE. */
    EXPIRED
}
