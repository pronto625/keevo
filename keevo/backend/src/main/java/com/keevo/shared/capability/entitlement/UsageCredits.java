package com.keevo.shared.capability.entitlement;

import java.util.UUID;

/**
 * Story 19.8 — purchased packs ({@code public.usage_credits}): units of a metric a tenant may consume once its plan's
 * MONTH or LIFETIME quota is used up. Internal port for the payments of 21.x (no purchase endpoint here). Joins the
 * caller's transaction.
 */
public interface UsageCredits {

    /**
     * Adds {@code n} units of {@code metric} to the tenant's pack (upsert).
     *
     * @param n {@code >= 1}
     * @return the units now remaining
     */
    long add(UUID tenantId, UsageMetric metric, int n);
}
