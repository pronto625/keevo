package com.keevo.subscription.plan.domain.port.out;

import com.keevo.shared.capability.entitlement.PlanQuota;
import com.keevo.shared.capability.entitlement.QuotaPeriod;
import com.keevo.shared.capability.entitlement.UsageCredits;
import com.keevo.shared.capability.entitlement.UsageMetric;
import com.keevo.shared.capability.entitlement.UsagePackSource;

import java.time.Instant;
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

    /**
     * Takes {@code n} units from the pack when at least {@code n} remain and journals the consumption (Story 21.3:
     * actor, the quota period that was exhausted) — one statement (guarded update + journal insert).
     */
    boolean consumeCredits(UUID tenantId, UsageMetric metric, int n, QuotaPeriod period, UUID actorId);

    /** Adds {@code n} units to the pack (upsert) and journals it — one statement; the units now remaining. */
    long addCredits(UUID tenantId, UsageMetric metric, int n, UsageCredits.Grant grant);

    /**
     * The tenant's counters of these period keys, its packs and (Story 21.3) the pack units consumed since
     * {@code consumedSince} per metric — one query.
     */
    Usage usage(UUID tenantId, Collection<String> periodKeys, Instant consumedSince);

    /**
     * Story 21.3 — the pack journal of the tenant of this schema, newest first, of one metric or all ({@code null}), strictly before the
     * keyset {@code (beforeAt, beforeId)} when given; actor names joined in the same query.
     */
    List<PackMovementRow> packMovements(String tenantSchema, UsageMetric metric, Instant beforeAt, UUID beforeId, int limit);

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
     * @param packsConsumed per metric: pack units consumed since the instant asked (Story 21.3)
     */
    record Usage(Map<UsageMetric, Map<String, Long>> counters, Map<UsageMetric, Long> credits,
                 Map<UsageMetric, Long> packsConsumed) {
    }

    /**
     * Story 21.3 — one pack movement.
     *
     * @param period    the quota period that was exhausted (consumption), {@code null} otherwise
     * @param actorName first and last name of the actor, {@code null} when unknown or none
     */
    record PackMovementRow(UUID id, UsageMetric metric, QuotaPeriod period, int delta, long remainingAfter,
                           UsagePackSource source, UUID actorId, String actorName, Instant at) {
    }
}
