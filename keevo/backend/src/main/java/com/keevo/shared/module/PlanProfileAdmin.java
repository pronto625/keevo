package com.keevo.shared.module;

import com.keevo.identity.api.PlanType;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Story 16.8 — super-admin editor of the plan profiles (MOD-FR13b): the limits of each plan type and the paid modules
 * it grants ({@code public.plan_profiles} + {@code public.profile_modules}). A change applies to the next check
 * without a restart. When the granted modules change, the plan-derived {@code tenant_modules} rows of every live
 * tenant on that plan type are re-synced by a background job (Story 16.4, {@code PROFILE_RESYNC}: its owner
 * notifications are sent from the job thread, never in the HTTP request; rows set by a super-admin are never
 * touched); tenants above a lowered limit keep their records.
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
     *         naming modules by display name) — nothing saved; {@code ADMIN_JOB_RUNNING} (409) when the
     *         composition changes while a re-sync job is still running — nothing saved
     */
    UpdateResult update(PlanType planType, Integer maxStores, Integer maxProducts, Integer maxEmployees,
                        Set<String> modules, UUID actorId);

    /**
     * Re-syncs the {@code PLAN} rows of every live tenant whose plan has this type (the work of the
     * {@code PROFILE_RESYNC} job; synchronous here). Owner notifications of rows switched off are sent after each
     * tenant's commit, on the calling thread.
     *
     * @param progress told {@code (tenants done, tenants of the plan type)}
     * @return same shape as the 16.5 backfill report ({@code modulesProvisioned} always 0)
     */
    ModuleBackfill.BackfillReport resync(PlanType planType, com.keevo.shared.job.ProgressListener progress);

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
     * @param jobId              the {@code PROFILE_RESYNC} job re-syncing the tenants on this plan type (its report has
     *                           the 16.5 backfill shape); {@code null} when the composition did not change
     */
    record UpdateResult(ProfileView profile, boolean compositionChanged, UUID jobId) {
    }
}
