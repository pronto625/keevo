package com.keevo.shared.capability.entitlement;

import com.keevo.identity.api.PlanKind;
import com.keevo.identity.api.PlanType;

import java.util.Set;

/**
 * Test fixture (Story 19.7a): the plan profiles of the V33 seed, now that no limit lives in the production code —
 * the free plan 1 store / 500 products / 2 employees without paid module, the trial and paid plans unlimited with
 * every paid module (kind default). Any other code is taken as free. Stored profiles ({@code fallback = false}).
 */
public final class TestPlanProfiles {

    public static final int FREE_MAX_STORES = 1;
    public static final int FREE_MAX_PRODUCTS = 500;
    public static final int FREE_MAX_EMPLOYEES = 2;

    /** Every plan answers its seeded profile. */
    public static final PlanProfiles DEFAULTS = TestPlanProfiles::profile;

    private TestPlanProfiles() {
    }

    /** The seeded profile of the plan (kind from the V42 seed, unknown codes free). */
    public static PlanProfile profile(PlanType planType) {
        PlanKind kind = kind(planType);
        if (kind == PlanKind.FREE) {
            return PlanProfile.of(planType, kind, FREE_MAX_STORES, FREE_MAX_PRODUCTS, FREE_MAX_EMPLOYEES, Set.of());
        }
        return PlanProfile.of(planType, kind, null, null, null, Set.of());
    }

    /**
     * The kind the seed gives the plan (19.7b: the built-in catalogue, {@link PlanCatalog#SEED} — the V42 codes and the
     * merged offer PRO / TEAM / BUSINESS / BUSINESS_TRIAL); any other code is free.
     */
    public static PlanKind kind(PlanType planType) {
        return PlanCatalog.SEED.find(planType).map(PlanDefinition::kind)
                .orElseGet(() -> planType.seedKind().orElse(PlanKind.FREE));
    }

    /** A plan view of the seeded plan with its seeded limits. */
    public static PlanView view(PlanType planType, boolean active, java.time.Instant expiresAt) {
        return new PlanView(planType, kind(planType), active, expiresAt, profile(planType));
    }
}
