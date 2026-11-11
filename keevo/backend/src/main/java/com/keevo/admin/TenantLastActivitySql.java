package com.keevo.admin;

import com.keevo.admin.crosstenant.TenantBranch;
import com.keevo.admin.crosstenant.TenantTable;
import com.keevo.shared.infrastructure.persistence.TenantSchema;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

/**
 * Single definition of a tenant's « Dernière activité » for the admin screens (tenants list and
 * its activity filter, operations activity table, inactive tenants).
 *
 * <p>It is the most recent <b>merchant</b> event of the tenant, never a technical write:
 * <ul>
 *   <li>latest sale ({@code COALESCE(occurred_at, created_at)}, capped at {@code now()}) in the tenant schema;</li>
 *   <li>latest audit entry written by one of the tenant's own users (OWNER / EMPLOYEE membership) —
 *       the automatic day closure ({@code DAY_CLOSED} by the SYSTEM actor, every evening for every
 *       active store), schedulers ({@code source = 'SYSTEM'}) and Super Admin actions are excluded;</li>
 *   <li>latest sync operation pushed by a device ({@code sync_operations_log.processed_at});</li>
 *   <li>latest authenticated app use by a tenant user ({@code public.user_daily_activity}, empty
 *       before V18 — the other sources cover the history).</li>
 * </ul>
 * {@code GREATEST} ignores NULLs, so the expression is NULL only when the tenant has none of them
 * (shown « — » / « Jamais »).
 */
public final class TenantLastActivitySql {

    /** Actor of automatic writes (automatic day closure). */
    static final String SYSTEM_ACTOR = "00000000-0000-0000-0000-000000000000";

    private TenantLastActivitySql() {
    }

    /**
     * Scalar SQL expression of the tenant's last activity (timestamptz, nullable). It binds
     * {@link #args(UUID)} — append those values at its position in the statement's parameters.
     *
     * @throws IllegalArgumentException when {@code schemaName} is not a valid tenant schema
     */
    public static String expression(String schemaName) {
        // Every leg present; the two bound values are the tenant id, see args(...) — one definition (19.11b).
        TenantBranch all = new TenantBranch(SYSTEM_ACTOR, null, schemaName, null, null, EnumSet.allOf(TenantTable.class));
        return expression(all, new ArrayList<>());
    }

    /**
     * Story 19.11b — the same expression for a cross-tenant branch: a leg whose table is absent from the tenant
     * schema (module never used, older version) is {@code NULL} instead of a failing statement. The tenant id is
     * appended to {@code params} at each position where it is bound, in order.
     *
     * @throws IllegalArgumentException when the branch's schema is not a valid tenant schema
     */
    public static String expression(TenantBranch tenant, List<Object> params) {
        String s = TenantSchema.validate(tenant.schemaName());
        // occurred_at comes from the device clock: a phone set in the future must not push the tenant's last
        // activity past « now ».
        String sales = tenant.has(TenantTable.SALES_CORE)
                ? "(SELECT MAX(LEAST(COALESCE(sa.occurred_at, sa.created_at), now())) FROM " + s + ".sales sa)"
                : "CAST(NULL AS timestamptz)";
        String audit = "CAST(NULL AS timestamptz)";
        if (tenant.has(TenantTable.AUDIT_LOG)) {
            audit = "(SELECT a.occurred_at FROM " + s + ".audit_log a "
                    + "  WHERE a.user_id <> '" + SYSTEM_ACTOR + "'::uuid "
                    + "    AND (a.source IS NULL OR a.source <> 'SYSTEM') "
                    + "    AND EXISTS (SELECT 1 FROM public.user_tenant_memberships m "
                    + "                WHERE m.user_id = a.user_id AND m.tenant_id = CAST(? AS uuid) "
                    + "                  AND m.role IN (" + MemberRolesSql.IN_LIST + ")) "
                    + "  ORDER BY a.occurred_at DESC LIMIT 1)";
            params.add(tenant.id());
        }
        String sync = tenant.has(TenantTable.SYNC_OPERATIONS_LOG)
                ? "(SELECT MAX(so.processed_at) FROM " + s + ".sync_operations_log so)"
                : "CAST(NULL AS timestamptz)";
        params.add(tenant.id());
        return "GREATEST(" + sales + ", " + audit + ", " + sync + ", "
                + "(SELECT MAX(u.first_seen_at) FROM public.user_daily_activity u WHERE u.tenant_id = CAST(? AS uuid)))";
    }

    /**
     * Parameters bound by {@link #expression(String)}, in order.
     *
     * @param tenantId the tenant id, as a {@link UUID} or its canonical string form
     */
    public static Object[] args(Object tenantId) {
        Object id = tenantId instanceof UUID ? tenantId : String.valueOf(tenantId);
        return new Object[]{id, id};
    }
}
