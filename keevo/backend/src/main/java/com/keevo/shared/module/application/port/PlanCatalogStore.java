package com.keevo.shared.module.application.port;

import com.keevo.shared.capability.entitlement.PlanDefinition;
import com.keevo.shared.capability.entitlement.PlanSettings;

/**
 * Story 19.7a — write side of the plan catalogue ({@code public.plans} + {@code public.plan_settings}). Runs on the
 * caller's transaction; every write invalidates the catalogue cache (at once, and again after the caller's commit) so
 * the next read sees it. The super-admin CRUD of the offer (Story 21.10) builds on it. Implemented by
 * {@code shared.module.adapter.out.persistence.JdbcPlanCatalog}.
 */
public interface PlanCatalogStore {

    /** Inserts or updates a plan (codes are never deleted: archive with {@link PlanDefinition#archivedAt()}). */
    void save(PlanDefinition plan);

    /** Replaces the plan settings (free plan, trial plan, trial length). */
    void saveSettings(PlanSettings settings);

    /** Drops the cached catalogue: the next read reloads it (a write made outside this port). */
    void invalidate();
}
