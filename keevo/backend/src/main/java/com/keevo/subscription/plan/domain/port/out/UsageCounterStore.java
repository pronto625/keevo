package com.keevo.subscription.plan.domain.port.out;

import com.keevo.shared.capability.entitlement.PlanQuota;
import com.keevo.shared.capability.entitlement.QuotaPeriod;
import com.keevo.shared.capability.entitlement.UsageMetric;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * Story 19.8 — persistence of the usage quotas ({@code public.plan_quotas}), counters ({@code public.usage_counters})
 * and packs ({@code public.usage_credits}). Every statement runs on the caller's transaction and is served by a
 * primary key.
 */
public interface UsageCounterStore {

    /**
     * The tenant (by id) with its current plan code ({@code public.tenants.plan_type}, kept in step with the
     * subscription by every plan change) and the plan's quotas — of {@code metric}, or of every metric when null.
     * One indexed query. Empty when the tenant does not exist.
     */
    Optional<TenantQuotas> quotasById(UUID tenantId, UsageMetric metric);

    /** Same, the tenant given by its schema. */
    Optional<TenantQuotas> quotasBySchema(String tenantSchema, UsageMetric metric);

    /**
     * The quotas of a plan — of {@code metric}, or of every metric when null (primary-key prefix). Used for a tenant
     * whose plan code is missing or not in the catalogue (its free plan's quotas apply).
     */
    List<PlanQuota> quotasOfPlan(String planCode, UsageMetric metric);

    /**
     * One upsert of the counter of this period kind and key: adds {@code n} when the result stays {@code <= limit}
     * ({@code null} limit: always, unguarded).
     *
     * @return the new value, empty when the quota is exhausted (nothing written)
     */
    OptionalLong increment(UUID tenantId, UsageMetric metric, QuotaPeriod period, String periodKey, int n,
                           Integer limit);

    /** The counter's value ({@code 0} when absent). */
    long used(UUID tenantId, UsageMetric metric, String periodKey);

    /** Takes {@code n} units from the pack when at least {@code n} remain (one guarded update). */
    boolean consumeCredits(UUID tenantId, UsageMetric metric, int n);

    /** Adds {@code n} units to the pack (upsert); the units now remaining. */
    long addCredits(UUID tenantId, UsageMetric metric, int n);

    /** The tenant's counters of these period keys and its packs (one query). */
    Usage usage(UUID tenantId, Collection<String> periodKeys);

    /**
     * Deletes the DAY counters whose key is before {@code dayCutoff} and the MONTH counters before
     * {@code monthCutoff}; LIFETIME counters are kept. One set-based DELETE per period kind, served by the partial
     * index on {@code (period, period_key)}.
     *
     * @return rows deleted
     */
    int purge(String dayCutoff, String monthCutoff);

    /** A tenant, its plan code and the plan's quotas (empty: unlimited). */
    record TenantQuotas(UUID tenantId, String planCode, List<PlanQuota> quotas) {
        public TenantQuotas {
            quotas = List.copyOf(quotas);
        }
    }

    /**
     * @param counters per metric, per period key: units used
     * @param credits  per metric: pack units remaining
     */
    record Usage(Map<UsageMetric, Map<String, Long>> counters, Map<UsageMetric, Long> credits) {
    }
}
