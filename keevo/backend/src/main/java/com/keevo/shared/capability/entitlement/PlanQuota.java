package com.keevo.shared.capability.entitlement;

import java.util.Objects;

/**
 * Story 19.8 — one quota of a plan ({@code public.plan_quotas}): at most {@code limit} units of {@code metric} per
 * {@code period}. A metric without any quota row is unlimited (still counted).
 *
 * @param limit {@code >= 0} ({@code 0}: the plan does not include the metric)
 */
public record PlanQuota(UsageMetric metric, QuotaPeriod period, int limit) {

    public PlanQuota {
        Objects.requireNonNull(metric, "metric");
        Objects.requireNonNull(period, "period");
        if (limit < 0) {
            throw new IllegalArgumentException("limit must be >= 0");
        }
    }
}
