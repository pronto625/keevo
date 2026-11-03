package com.keevo.shared.capability.entitlement;

import com.keevo.identity.api.PlanType;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Story 16.8 — live read of the plan profiles ({@code public.plan_profiles} + {@code public.profile_modules}): the
 * single source of the plan limits and of the paid modules each plan grants. Every call reads the database, so a
 * super-admin change applies to the next check without a restart.
 *
 * <p>Story 19.7a — no limit lives in the code: a plan without a stored profile gets the free plan's profile (fail
 * closed) with an ERROR log; a profile that cannot be read (database fault) answers the last one read, flagged
 * {@link PlanProfile#fallback()} — never an exception. Implemented by the core
 * ({@code shared.module.adapter.out.persistence.JdbcPlanProfileStore}).
 */
@FunctionalInterface
public interface PlanProfiles {

    /** The profile of the plan, or its fallback. */
    PlanProfile profile(PlanType planType);

    /**
     * The profiles of several plans, in their order (Story 19.7a: the core reads them in one query, same fallbacks
     * as {@link #profile(PlanType)}).
     */
    default Map<PlanType, PlanProfile> profiles(Collection<PlanType> planTypes) {
        Map<PlanType, PlanProfile> result = new LinkedHashMap<>();
        planTypes.forEach(t -> result.put(t, profile(t)));
        return result;
    }

    /**
     * The profile of a plan given by code.
     *
     * @return {@code null} for a null or malformed code
     */
    default PlanProfile profile(String planType) {
        return PlanType.parse(planType).map(this::profile).orElse(null);
    }
}
