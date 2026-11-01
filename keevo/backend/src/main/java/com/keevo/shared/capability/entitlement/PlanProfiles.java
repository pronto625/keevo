package com.keevo.shared.capability.entitlement;

import com.keevo.identity.api.PlanType;

/**
 * Story 16.8 — live read of the plan profiles ({@code public.plan_profiles} + {@code public.profile_modules}): the
 * single source of the plan limits and of the paid modules each plan type grants. Every call reads the database (no
 * cache outliving the call), so a super-admin change applies to the next check without a restart.
 *
 * <p>When a profile cannot be read (row missing, database fault) the {@link PlanType} constants are used instead
 * ({@link PlanProfile#fromEnum}) and an ERROR is logged — never an exception. Implemented by the core
 * ({@code shared.module.adapter.out.persistence.JdbcPlanProfileStore}); joins the caller's transaction.
 */
public interface PlanProfiles {

    /** The profile of the plan type, or the enum fallback. */
    PlanProfile profile(PlanType planType);

    /**
     * The profile of a plan type given by name ({@code FREE} | {@code PREMIUM_TRIAL} | {@code PREMIUM}).
     *
     * @return {@code null} for a null or unknown plan type
     */
    default PlanProfile profile(String planType) {
        if (planType == null) {
            return null;
        }
        PlanType type;
        try {
            type = PlanType.valueOf(planType);
        } catch (IllegalArgumentException e) {
            return null;
        }
        return profile(type);
    }

    /** No database: every plan type answers its enum fallback (tests, slices without the core adapter). */
    PlanProfiles ENUM_DEFAULTS = PlanProfile::fromEnum;
}
