package com.keevo.subscription.api;

import com.keevo.identity.api.PlanType;

import java.time.Instant;

/**
 * The tenant's current subscription plan as seen by other modules (Story 12.2d). Limits come from
 * the {@link PlanType} (until 16.8 introduces configurable plan profiles).
 *
 * @param active    true when the subscription status is ACTIVE
 * @param expiresAt null for FREE
 */
public record PlanView(PlanType planType, boolean active, Instant expiresAt) {

    public PlanView {
        if (planType == null) { // subscriptions.plan_type is NOT NULL: a null here is a programming error
            throw new IllegalArgumentException("planType is required");
        }
    }

    public boolean isPremium() {
        return planType == PlanType.PREMIUM;
    }

    public boolean isPremiumTrial() {
        return planType == PlanType.PREMIUM_TRIAL;
    }

    public boolean isFree() {
        return planType == PlanType.FREE;
    }

    public int maxStores() {
        return planType.getMaxStores();
    }

    public int maxProducts() {
        return planType.getMaxProducts();
    }

    public int maxEmployees() {
        return planType.getMaxEmployees();
    }
}
