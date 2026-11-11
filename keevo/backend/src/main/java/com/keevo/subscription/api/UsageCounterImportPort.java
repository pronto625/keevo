package com.keevo.subscription.api;

import com.keevo.shared.capability.entitlement.QuotaPeriod;
import com.keevo.shared.capability.entitlement.UsageMetric;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Story 24.5 — the usage counters a migrated owner already consumed in Bref (this month, today, for life), written
 * into {@code public.usage_counters} so the quotas keep counting where Bref stopped. One statement; an existing counter
 * keeps the higher value (never lowered). Joins the caller's transaction (MANDATORY).
 */
public interface UsageCounterImportPort {

    /** @return the number of counters inserted or raised */
    int importCounters(UUID tenantId, List<ImportedCounter> counters);

    /**
     * @param periodKey {@link QuotaPeriod#key} of the period ({@code 2026-11}, {@code 2026-11-04},
     *                  {@link QuotaPeriod#LIFETIME_KEY})
     * @param used      {@code >= 0}
     */
    record ImportedCounter(UsageMetric metric, QuotaPeriod period, String periodKey, long used) {

        public ImportedCounter {
            Objects.requireNonNull(metric, "metric");
            Objects.requireNonNull(period, "period");
            Objects.requireNonNull(periodKey, "periodKey");
            if (used < 0) {
                throw new IllegalArgumentException("used must not be negative");
            }
        }
    }
}
