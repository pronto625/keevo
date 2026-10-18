package com.keevo.admin;

import com.keevo.shared.infrastructure.persistence.TenantSchema;

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
        String s = TenantSchema.validate(schemaName);
        return "GREATEST("
                // occurred_at comes from the device clock: a phone set in the future must not push
                // the tenant's last activity past « now ».
                + "(SELECT MAX(LEAST(COALESCE(sa.occurred_at, sa.created_at), now())) FROM " + s + ".sales sa), "
                + "(SELECT a.occurred_at FROM " + s + ".audit_log a "
                + "  WHERE a.user_id <> '" + SYSTEM_ACTOR + "'::uuid "
                + "    AND (a.source IS NULL OR a.source <> 'SYSTEM') "
                + "    AND EXISTS (SELECT 1 FROM public.user_tenant_memberships m "
                + "                WHERE m.user_id = a.user_id AND m.tenant_id = CAST(? AS uuid) "
                + "                  AND m.role IN ('OWNER', 'EMPLOYEE')) "
                + "  ORDER BY a.occurred_at DESC LIMIT 1), "
                + "(SELECT MAX(so.processed_at) FROM " + s + ".sync_operations_log so), "
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
