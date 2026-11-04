package com.keevo.shared.capability.entitlement;

import java.util.UUID;

/**
 * Story 19.8 — usage counters and plan quotas (documents, SMS, emails, AI credits). Implemented by subscription
 * ({@code subscription.plan.application.service.UsageQuotaService}); declared in the core so a consumer (invoicing,
 * messaging, AI) depends on {@code shared} only.
 *
 * <p>An increment joins the caller's transaction (it must exist, {@code MANDATORY}): a later failure of the caller
 * rolls the count back. <b>Atomicity across periods relies on that transaction:</b> the periods are upserted one after
 * the other, so a refusal on a later period ({@code QUOTA_EXCEEDED}) leaves the earlier upserts in the transaction —
 * the caller must let the exception roll it back (never catch it and commit). Each quota period of the metric is checked and incremented by one guarded upsert, in the
 * order DAY, MONTH, LIFETIME — concurrent devices can never overshoot a quota. An exhausted MONTH or LIFETIME quota
 * falls back on the tenant's purchased pack ({@link UsageCredits}); the DAY cap never does. MONTH and DAY are always
 * counted (guarded when the plan limits them, unguarded otherwise), so « Mon plan » shows the usage and a quota added
 * later or a plan change mid-month starts from the real figure; LIFETIME is counted only under a LIFETIME quota.
 *
 * <p>Core writes (sales, payments, stock, non-document sync) are never quota-checked.
 */
public interface UsageQuotaPort {

    /** No quota, nothing counted (contexts without usage quotas, tests): increments are no-ops, status is empty. */
    UsageQuotaPort NONE = new UsageQuotaPort() {
        @Override
        public void assertAndIncrement(UUID tenantId, UsageMetric metric, int n) {
        }

        @Override
        public void assertAndIncrementForSchema(String tenantSchema, UsageMetric metric, int n) {
        }

        @Override
        public UsageStatus status(UUID tenantId) {
            return UsageStatus.empty();
        }

        @Override
        public UsageStatus statusForSchema(String tenantSchema) {
            return UsageStatus.empty();
        }
    };

    /**
     * Counts {@code n} units of {@code metric} for the tenant, or refuses.
     *
     * @param n {@code >= 1}
     * @throws com.keevo.shared.domain.exception.DomainException {@code QUOTA_EXCEEDED} (403) with details
     *         {@code metric, period, limit, used, resetAt, planCode, planLabel}; nothing counted (the caller's
     *         transaction rolls back)
     */
    void assertAndIncrement(UUID tenantId, UsageMetric metric, int n);

    /**
     * Same, for the tenant of this schema (the tenant convention of the module APIs): the tenant is resolved in the
     * same indexed read as its quotas.
     */
    void assertAndIncrementForSchema(String tenantSchema, UsageMetric metric, int n);

    /** Read-only usage of the tenant: per metric and quota, and its pack credits. */
    UsageStatus status(UUID tenantId);

    /** Same, for the tenant of this schema. */
    UsageStatus statusForSchema(String tenantSchema);
}
