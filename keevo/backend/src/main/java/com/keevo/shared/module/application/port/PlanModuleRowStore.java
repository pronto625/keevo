package com.keevo.shared.module.application.port;

import com.keevo.shared.module.TenantModuleStatus;
import com.keevo.shared.module.application.port.TenantModuleStatePort.Plan;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Story 16.5 — persistence of the plan-derived {@code tenant_modules} rows. Every call runs on the caller's
 * transaction (JDBC statements join its connection). Implemented by
 * {@code shared.module.adapter.out.persistence.JdbcPlanModuleRowStore}.
 */
public interface PlanModuleRowStore {

    /** Row provenance ({@code tenant_modules.origin}). */
    enum Origin { SYSTEM, PLAN, ADMIN }

    /**
     * The tenant's plan, read on the caller's transaction (sees an uncommitted plan change), exactly as the module
     * registry reads it: oldest {@code subscriptions} row (locked {@code FOR UPDATE}) + latest
     * {@code tenant_plan_events} cause. No {@code subscriptions} table, or any fault → {@link Plan#UNREADABLE}.
     */
    Plan readPlan(UUID tenantId, String tenantSchema);

    /** Every {@code tenant_modules} row of the tenant, module id → row. */
    Map<String, StoredRow> findRows(UUID tenantId);

    /**
     * Inserts or rewrites the row as {@code origin = 'PLAN'}; an existing row of another origin is left as it is.
     * {@code disabled_at} is {@code now} when the row turns {@code DISABLED} (kept while it stays so), null
     * otherwise; {@code activated_at} is kept, set to {@code now} on a first {@code TRIAL}/{@code ACTIVE} write.
     *
     * @return whether a row was written
     */
    boolean upsertPlanRow(UUID tenantId, String moduleId, TenantModuleStatus status, String tier, Instant expiresAt,
                          Instant graceUntil, Instant now);

    /** Every live tenant ({@code status <> 'DELETED'}), ordered by code. */
    List<LiveTenant> liveTenants();

    /**
     * Story 16.8 — the live tenants whose {@code public.tenants.plan_type} is the given one, ordered by code (a
     * pre-selection: the row sync re-checks the tenant's own subscription).
     */
    default List<LiveTenant> liveTenants(String planType) {
        return liveTenants();
    }

    /**
     * @param origin             the stored provenance
     * @param provisionedVersion the module's recorded provisioned version ({@code null}: none)
     */
    record StoredRow(Origin origin, TenantModuleStatus status, String tier, Instant expiresAt, Instant graceUntil,
                     String provisionedVersion) {
    }

    /**
     * @param id            {@code public.tenants.id}
     * @param code          public code ({@code KV-XXXXXX}) — the only identity used in reports and logs
     * @param tenantSchema  schema name (not validated here)
     * @param schemaPresent whether the schema exists ({@code pg_namespace})
     */
    record LiveTenant(UUID id, String code, String tenantSchema, boolean schemaPresent) {
    }
}
