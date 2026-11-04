package com.keevo.shared.capability.entitlement;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Story 19.8 — read-only usage of a tenant ({@code GET /subscription/me}).
 *
 * @param quotas  per metric: one entry per quota of the plan, or one MONTH entry without limit for a metric without
 *                any quota (its usage is still counted)
 * @param credits pack units remaining per metric (metrics without a pack absent)
 */
public record UsageStatus(List<QuotaUsage> quotas, Map<UsageMetric, Long> credits) {

    public UsageStatus {
        quotas = List.copyOf(quotas);
        credits = Map.copyOf(credits);
    }

    /** No usage known. */
    public static UsageStatus empty() {
        return new UsageStatus(List.of(), Map.of());
    }

    /**
     * @param limit     {@code null} = unlimited
     * @param used      units counted in the current period
     * @param remaining {@code max(0, limit - used)}, {@code null} when unlimited
     * @param resetAt   start of the next period (WAT), {@code null} for LIFETIME
     */
    public record QuotaUsage(UsageMetric metric, QuotaPeriod period, Integer limit, long used, Long remaining,
                             Instant resetAt) {

        public QuotaUsage {
            Objects.requireNonNull(metric, "metric");
            Objects.requireNonNull(period, "period");
        }

        /** French name of the metric. */
        public String metricLabel() {
            return metric.label();
        }

        /** French wording of the period. */
        public String periodLabel() {
            return period.label();
        }
    }
}
