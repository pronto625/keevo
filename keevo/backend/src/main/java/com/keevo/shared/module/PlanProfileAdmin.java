package com.keevo.shared.module;

import com.keevo.identity.api.PlanType;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Story 16.8 — super-admin editor of the plan profiles (MOD-FR13b): the limits of each plan type and the paid modules
 * it grants ({@code public.plan_profiles} + {@code public.profile_modules}). A change applies to the next check
 * without a restart. When the granted modules change, the plan-derived {@code tenant_modules} rows of every live
 * tenant on that plan type are re-synced in the same call (rows set by a super-admin are never touched); tenants
 * above a lowered limit keep their records.
 */
public interface PlanProfileAdmin {

    /** Every plan profile, in plan order ({@code FREE}, {@code PREMIUM_TRIAL}, {@code PREMIUM}). */
    List<ProfileView> list();

    /**
     * Replaces the profile of a plan type.
     *
     * @param modules ids of installed <b>paid</b> modules only — the full list granted: every other installed paid
     *                module is recorded as not granted
     * @throws com.keevo.shared.domain.exception.DomainException {@code PLAN_PROFILE_INVALID} (422, French reason
     *         naming modules by display name) — nothing saved
     */
    UpdateResult update(PlanType planType, Integer maxStores, Integer maxProducts, Integer maxEmployees,
                        Set<String> modules, UUID actorId);

    /**
     * @param planType     {@code FREE} | {@code PREMIUM_TRIAL} | {@code PREMIUM}
     * @param label        French name of the plan
     * @param maxStores    {@code null} = unlimited
     * @param maxProducts  {@code null} = unlimited
     * @param maxEmployees {@code null} = unlimited
     * @param modules      the paid modules granted (explicitly, or by the plan type's default), by display name order
     * @param fallback     the stored profile is missing or unreadable: these are the built-in defaults
     */
    record ProfileView(String planType, String label, Integer maxStores, Integer maxProducts, Integer maxEmployees,
                       List<ModuleRef> modules, boolean fallback) {

        public ProfileView {
            modules = List.copyOf(modules);
        }
    }

    /** A module by id and French display name. */
    record ModuleRef(String id, String displayName) {
    }

    /**
     * @param profile            the profile as saved
     * @param compositionChanged whether the granted modules changed
     * @param resync             re-sync of the tenants on this plan type (same shape as the 16.5 backfill report,
     *                           {@code modulesProvisioned} always 0); {@code null} when the composition did not change
     */
    record UpdateResult(ProfileView profile, boolean compositionChanged, ModuleBackfill.BackfillReport resync) {
    }
}
