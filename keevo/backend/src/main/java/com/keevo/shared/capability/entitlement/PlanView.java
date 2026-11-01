package com.keevo.shared.capability.entitlement;

import com.keevo.identity.api.PlanType;

import java.time.Instant;

/**
 * The tenant's current subscription plan as seen by other modules (Story 12.2d). Since Story 16.8 the limits come
 * from the plan profile ({@link PlanProfiles}) read when the view is built; {@code null} = unlimited.
 *
 * @param active       true when the subscription status is ACTIVE
 * @param expiresAt    null for FREE
 * @param maxStores    store limit, {@code null} = unlimited
 * @param maxProducts  active-product limit, {@code null} = unlimited
 * @param maxEmployees employee limit, {@code null} = unlimited
 */
public record PlanView(PlanType planType, boolean active, Instant expiresAt, Integer maxStores, Integer maxProducts,
                       Integer maxEmployees) {

    public PlanView {
        if (planType == null) { // subscriptions.plan_type is NOT NULL: a null here is a programming error
            throw new IllegalArgumentException("planType is required");
        }
    }

    /** A view whose limits are the built-in {@link PlanType} defaults (tests, no profile at hand). */
    public PlanView(PlanType planType, boolean active, Instant expiresAt) {
        this(planType, active, expiresAt, planType != null ? PlanProfile.fromEnum(planType) : null);
    }

    /** A view whose limits are those of the given profile ({@code null}: unlimited). */
    public PlanView(PlanType planType, boolean active, Instant expiresAt, PlanProfile profile) {
        this(planType, active, expiresAt, profile != null ? profile.maxStores() : null,
                profile != null ? profile.maxProducts() : null, profile != null ? profile.maxEmployees() : null);
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
}
