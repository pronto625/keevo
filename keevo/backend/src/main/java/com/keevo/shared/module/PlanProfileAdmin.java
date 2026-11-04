package com.keevo.shared.module;

import com.keevo.identity.api.PlanType;
import com.keevo.shared.capability.entitlement.PlanQuota;

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

    /** The profile of every plan of the catalogue, in its display order (Story 19.7a). */
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
    default UpdateResult update(PlanType planType, Integer maxStores, Integer maxProducts, Integer maxEmployees,
                                Set<String> modules, UUID actorId) {
        return update(planType, maxStores, maxProducts, maxEmployees, LimitPatch.KEEP, modules, actorId);
    }

    /**
     * Story 19.7b — same, with the per-store employee limit.
     *
     * @param maxEmployeesPerStore {@link LimitPatch#KEEP} keeps the stored limit; {@link LimitPatch.Set} replaces it
     *                             (positive, or {@code null} = unlimited)
     */
    default UpdateResult update(PlanType planType, Integer maxStores, Integer maxProducts, Integer maxEmployees,
                                LimitPatch maxEmployeesPerStore, Set<String> modules, UUID actorId) {
        return update(planType, maxStores, maxProducts, maxEmployees, maxEmployeesPerStore, modules,
                QuotaPatch.KEEP, actorId);
    }

    /**
     * Story 19.8 — same, with the plan's usage quotas.
     *
     * @param quotas {@link QuotaPatch#KEEP} keeps the stored quotas; {@link QuotaPatch.Replace} replaces them all
     *               (limit {@code >= 0}, no duplicate metric and period; a metric without quota is unlimited). Applies
     *               to the next increment.
     * @throws com.keevo.shared.domain.exception.DomainException {@code PLAN_PROFILE_INVALID} (422, French reason) for
     *         an invalid quota list — nothing saved
     */
    UpdateResult update(PlanType planType, Integer maxStores, Integer maxProducts, Integer maxEmployees,
                        LimitPatch maxEmployeesPerStore, Set<String> modules, QuotaPatch quotas, UUID actorId);

    /** Story 19.8 — the optional quotas of an update: kept as stored, or replaced. */
    sealed interface QuotaPatch {

        /** Keep the stored quotas (a client that does not send them). */
        QuotaPatch KEEP = new Keep();

        /** Keep the stored quotas. */
        record Keep() implements QuotaPatch {
        }

        /**
         * Replace every quota of the plan by these. The list is taken as given (a {@code null} element is refused by
         * the service with a 422); the service saves an unmodifiable copy once validated.
         */
        record Replace(List<PlanQuota> quotas) implements QuotaPatch {
        }
    }

    /** Story 19.7b — an optional limit of an update: kept as stored, or set (a {@code null} value = unlimited). */
    sealed interface LimitPatch {

        /** Keep the stored value (a client that does not send it). */
        LimitPatch KEEP = new Keep();

        /** Keep the stored value. */
        record Keep() implements LimitPatch {
        }

        /** Set the value ({@code null} = unlimited). */
        record Set(Integer value) implements LimitPatch {
        }

        /** The value to save, given the stored one. */
        default Integer resolve(Integer stored) {
            return this instanceof Set(Integer value) ? value : stored;
        }
    }

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
     * @param planType     a plan code of the catalogue ({@code public.plans})
     * @param label        French name of the plan
     * @param maxStores    {@code null} = unlimited
     * @param maxProducts  {@code null} = unlimited
     * @param maxEmployees {@code null} = unlimited
     * @param modules      the paid modules granted (explicitly, or by the plan type's default), by display name order
     * @param fallback     the stored profile is missing or unreadable: these values come from the free plan's
     *                     profile or the last one read (Story 19.7a)
     * @param kind         Story 19.7b — the plan's kind ({@code FREE|TRIAL|PAID})
     * @param archived     Story 19.7b — the plan is archived (kept for its tenants, no longer offered)
     * @param maxEmployeesPerStore Story 19.7b — active employees per store, {@code null} = unlimited
     * @param quotas       Story 19.8 — the plan's usage quotas (a metric absent is unlimited)
     */
    record ProfileView(String planType, String label, Integer maxStores, Integer maxProducts, Integer maxEmployees,
                       List<ModuleRef> modules, boolean fallback, String kind, boolean archived,
                       Integer maxEmployeesPerStore, List<QuotaRef> quotas) {

        public ProfileView {
            modules = List.copyOf(modules);
            quotas = List.copyOf(quotas);
        }
    }

    /**
     * Story 19.8 — one usage quota of a plan.
     *
     * @param metric      {@code DOCUMENTS | SMS | EMAILS | AI_CREDITS}
     * @param metricLabel French name (« Documents », « SMS », « Emails », « Crédits IA »)
     * @param period      {@code MONTH | DAY | LIFETIME}
     * @param periodLabel French wording (« par mois », « par jour », « à vie »)
     * @param limit       {@code >= 0}
     */
    record QuotaRef(String metric, String metricLabel, String period, String periodLabel, int limit) {

        public static QuotaRef of(PlanQuota q) {
            return new QuotaRef(q.metric().name(), q.metric().label(), q.period().name(), q.period().label(),
                    q.limit());
        }
    }

    /**
     * A module by id and French display name. Story 16.14: {@code moduleId} repeats {@code id} (same value), the name
     * every other admin module reference uses; {@code id} stays for the clients already reading it.
     */
    record ModuleRef(String id, String displayName) {

        /** Story 16.14 — same value as {@code id}, serialised as {@code moduleId} (derived, not a component). */
        @com.fasterxml.jackson.annotation.JsonProperty("moduleId")
        public String moduleId() {
            return id;
        }
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
