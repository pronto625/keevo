package com.keevo.subscription.plan.adapter.in.rest.dto;

import com.keevo.shared.capability.entitlement.UsageStatus;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SubscriptionResponse — API response DTO for subscription info and usage counts.
 *
 * <p>Null values for maxStores/maxProducts/maxEmployees indicate "unlimited"
 * (as configured in the plan's profile).
 *
 * <p>Story 19.7b — additive fields: the plan's own code, French label and kind ({@code FREE|TRIAL|PAID}) and the
 * per-store employee limit. {@code planType} stays the V1 value.
 *
 * <p>Story 19.8 — additive fields: {@code quotas} (per metric and quota: limit, used, remaining, reset instant; an
 * unlimited metric appears once, MONTH, limit {@code null}) and {@code credits} (pack units remaining per metric).
 *
 * <p>Story 21.3 — additive field {@code packsUsedThisMonth}: pack units consumed since the 1st of the month (WAT), by
 * metric (shown next to the month quota; the detail is {@code GET /subscription/me/pack-movements}).
 */
public record SubscriptionResponse(
        String planType,           // V1 code of the plan's kind (19.7a): FREE | PREMIUM_TRIAL | PREMIUM
        String status,             // "ACTIVE" | "SUSPENDED" | "EXPIRED"
        String expiresAt,          // ISO 8601; null for FREE plan
        Integer maxStores,         // null = unlimited
        Integer maxProducts,       // null = unlimited
        Integer maxEmployees,      // null = unlimited
        int currentStores,
        Integer currentProducts,   // 16.8: null = the count could not be read (unknown)
        int currentEmployees,
        String planCode,           // 19.7b: the plan's own code (TEAM, BUSINESS_TRIAL…)
        String planLabel,          // 19.7b: French label of the plan
        String planKind,           // 19.7b: FREE | TRIAL | PAID
        Integer maxEmployeesPerStore, // 19.7b: null = unlimited
        List<Quota> quotas,          // 19.8: usage quotas of the plan
        Map<String, Long> credits,   // 19.8: pack units remaining, by metric
        Map<String, Long> packsUsedThisMonth // 21.3: pack units consumed this month, by metric
) {

    /** The 19.8 fields (no month pack consumption). */
    public SubscriptionResponse(String planType, String status, String expiresAt, Integer maxStores,
                                Integer maxProducts, Integer maxEmployees, int currentStores,
                                Integer currentProducts, int currentEmployees, String planCode, String planLabel,
                                String planKind, Integer maxEmployeesPerStore, List<Quota> quotas,
                                Map<String, Long> credits) {
        this(planType, status, expiresAt, maxStores, maxProducts, maxEmployees, currentStores, currentProducts,
                currentEmployees, planCode, planLabel, planKind, maxEmployeesPerStore, quotas, credits, null);
    }

    /** The 19.7b fields, without usage (quotas and credits null). */
    public SubscriptionResponse(String planType, String status, String expiresAt, Integer maxStores,
                                Integer maxProducts, Integer maxEmployees, int currentStores,
                                Integer currentProducts, int currentEmployees, String planCode, String planLabel,
                                String planKind, Integer maxEmployeesPerStore) {
        this(planType, status, expiresAt, maxStores, maxProducts, maxEmployees, currentStores, currentProducts,
                currentEmployees, planCode, planLabel, planKind, maxEmployeesPerStore, null, null, null);
    }

    /** The V1 fields only (additive fields null). */
    public SubscriptionResponse(String planType, String status, String expiresAt, Integer maxStores,
                                Integer maxProducts, Integer maxEmployees, int currentStores,
                                Integer currentProducts, int currentEmployees) {
        this(planType, status, expiresAt, maxStores, maxProducts, maxEmployees, currentStores, currentProducts,
                currentEmployees, null, null, null, null);
    }

    /**
     * Story 19.8 — one usage quota.
     *
     * @param metric      {@code DOCUMENTS | SMS | EMAILS | AI_CREDITS}
     * @param metricLabel French name (« Documents », « SMS », « Emails », « Crédits IA »)
     * @param period      {@code MONTH | DAY | LIFETIME}
     * @param periodLabel French wording (« par mois », « par jour », « à vie »)
     * @param limit     {@code null} = unlimited
     * @param remaining {@code null} = unlimited
     * @param resetAt   ISO-8601 instant of the next period (WAT); {@code null} for LIFETIME
     */
    public record Quota(String metric, String metricLabel, String period, String periodLabel, Integer limit,
                        long used, Long remaining, String resetAt) {

        public static Quota of(UsageStatus.QuotaUsage q) {
            return new Quota(q.metric().name(), q.metricLabel(), q.period().name(), q.periodLabel(), q.limit(),
                    q.used(), q.remaining(),
                    q.resetAt() != null ? q.resetAt().toString() : null);
        }
    }

    /** Story 19.8 — the usage fields of a status. */
    public static List<Quota> quotas(UsageStatus status) {
        return status.quotas().stream().map(Quota::of).toList();
    }

    /** Story 19.8 — pack credits by metric name, in metric order. */
    public static Map<String, Long> credits(UsageStatus status) {
        return byMetric(status.credits());
    }

    /** Story 21.3 — pack units consumed this month by metric name, in metric order. */
    public static Map<String, Long> packsUsedThisMonth(UsageStatus status) {
        return byMetric(status.packsConsumedThisMonth());
    }

    private static Map<String, Long> byMetric(Map<com.keevo.shared.capability.entitlement.UsageMetric, Long> values) {
        Map<String, Long> out = new LinkedHashMap<>();
        values.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(e -> out.put(e.getKey().name(), e.getValue()));
        return out;
    }
}
