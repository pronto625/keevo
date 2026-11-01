package com.keevo.shared.module.application.port;

import com.keevo.identity.api.PlanType;
import com.keevo.shared.capability.entitlement.PlanProfile;

import java.util.Optional;
import java.util.UUID;

/**
 * Story 16.8 — write side of the plan profiles ({@code public.plan_profiles} + {@code public.profile_modules}), used
 * by the super-admin profile editor. Runs on the caller's transaction. Implemented by
 * {@code shared.module.adapter.out.persistence.JdbcPlanProfileStore}.
 */
public interface PlanProfileStore {

    /** The stored profile, without any fallback; empty when its row is missing. Locks the row ({@code FOR UPDATE}). */
    Optional<PlanProfile> findForUpdate(PlanType planType);

    /** Upserts the limits and replaces the granted modules of {@code profile.planType()}. */
    void save(PlanProfile profile, UUID actorId);
}
