package com.keevo.shared.capability.entitlement;

import com.keevo.identity.api.PlanKind;
import com.keevo.identity.api.PlanType;

import java.time.Instant;

/**
 * The tenant's current subscription plan as seen by other modules (Story 12.2d). Since Story 16.8 the limits come
 * from the plan profile ({@link PlanProfiles}) read when the view is built; {@code null} = unlimited. Since Story
 * 19.7a the view carries the plan's kind (catalogue), which gives the V1 code of a refusal.
 *
 * @param kind         the plan's kind ({@link PlanCatalog})
 * @param active       true when the subscription status is ACTIVE
 * @param expiresAt    null for a free plan
 * @param maxStores    store limit, {@code null} = unlimited
 * @param maxProducts  active-product limit, {@code null} = unlimited
 * @param maxEmployees employee limit, {@code null} = unlimited
 * @param maxEmployeesPerStore Story 19.7b — active employees per store, {@code null} = unlimited
 */
public record PlanView(PlanType planType, PlanKind kind, boolean active, Instant expiresAt, Integer maxStores,
                       Integer maxProducts, Integer maxEmployees, Integer maxEmployeesPerStore) {

    public PlanView {
        if (planType == null) { // subscriptions.plan_type is NOT NULL: a null here is a programming error
            throw new IllegalArgumentException("planType is required");
        }
        if (kind == null) {
            throw new IllegalArgumentException("kind is required");
        }
    }

    /** A view without a per-store employee limit. */
    public PlanView(PlanType planType, PlanKind kind, boolean active, Instant expiresAt, Integer maxStores,
                    Integer maxProducts, Integer maxEmployees) {
        this(planType, kind, active, expiresAt, maxStores, maxProducts, maxEmployees, null);
    }

    /** A view whose limits are those of the given profile ({@code null}: unlimited). */
    public PlanView(PlanType planType, PlanKind kind, boolean active, Instant expiresAt, PlanProfile profile) {
        this(planType, kind, active, expiresAt, profile != null ? profile.maxStores() : null,
                profile != null ? profile.maxProducts() : null, profile != null ? profile.maxEmployees() : null,
                profile != null ? profile.maxEmployeesPerStore() : null);
    }

    /** The plan code a V1 client understands ({@link PlanKind#v1Plan()}). */
    public PlanType v1Plan() {
        return kind.v1Plan();
    }
}
