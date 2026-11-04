package com.keevo.shared.module.application.port;

import com.keevo.identity.api.PlanType;
import com.keevo.shared.capability.entitlement.PlanQuota;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Story 19.8 — the usage quotas of the plans ({@code public.plan_quotas}) for the super-admin profile editor: read
 * with the profiles, replaced with them. Runs on the caller's transaction. Implemented by
 * {@code shared.module.adapter.out.persistence.JdbcPlanQuotaStore}.
 */
public interface PlanQuotaStore {

    /** No quota at all (tests, contexts without quotas). */
    PlanQuotaStore NONE = new PlanQuotaStore() {
        @Override
        public Map<PlanType, List<PlanQuota>> quotas(Collection<PlanType> planTypes) {
            return Map.of();
        }

        @Override
        public void replace(PlanType planType, List<PlanQuota> quotas) {
        }
    };

    /** The quotas of these plans (one query); a plan without quota is absent. */
    Map<PlanType, List<PlanQuota>> quotas(Collection<PlanType> planTypes);

    /** Replaces every quota of the plan (an empty list: unlimited everywhere). */
    void replace(PlanType planType, List<PlanQuota> quotas);
}
