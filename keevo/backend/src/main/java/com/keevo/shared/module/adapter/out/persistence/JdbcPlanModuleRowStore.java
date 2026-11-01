package com.keevo.shared.module.adapter.out.persistence;

import com.keevo.shared.infrastructure.persistence.TenantSchema;
import com.keevo.shared.module.TenantModuleStatus;
import com.keevo.shared.module.application.port.PlanModuleRowStore;
import com.keevo.shared.module.application.port.TenantModuleStatePort.Plan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * JDBC adapter of {@link PlanModuleRowStore} on {@code public.tenant_modules} (Story 16.5). Schema-qualified, bind
 * parameters only; every statement joins the caller's transaction ({@link JdbcTemplate} on the shared
 * {@link DataSource}). The plan is read with the module registry's own statements
 * ({@link JdbcTenantModuleStateStore#readPlan(java.sql.Connection, String, UUID, boolean)}), but on the
 * transaction's connection and with the subscription row locked ({@code FOR UPDATE}): a sync waits for a concurrent
 * plan writer's commit and never derives rows from a stale plan. The existence of {@code subscriptions} is checked
 * first ({@code to_regclass}) so a tenant without the table never aborts the transaction: it is reported unreadable.
 */
@Component
@DependsOnDatabaseInitialization
public class JdbcPlanModuleRowStore implements PlanModuleRowStore {

    private static final Logger log = LoggerFactory.getLogger(JdbcPlanModuleRowStore.class);

    static final String SUBSCRIPTIONS_PRESENT_SQL = "SELECT to_regclass(?) IS NOT NULL";

    static final String FIND_ROWS_SQL = """
            SELECT module_id, origin, status, tier, expires_at, grace_until, provisioned_version
            FROM public.tenant_modules WHERE tenant_id = ?
            """;

    /**
     * Insert, or rewrite a {@code PLAN} row only ({@code WHERE origin = 'PLAN'}): an {@code ADMIN} / {@code SYSTEM}
     * row created concurrently is never overwritten.
     */
    static final String UPSERT_PLAN_ROW_SQL = """
            INSERT INTO public.tenant_modules (tenant_id, module_id, status, tier, activated_at, expires_at,
                                               grace_until, disabled_at, origin)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'PLAN')
            ON CONFLICT (tenant_id, module_id) DO UPDATE SET
                status = EXCLUDED.status, tier = EXCLUDED.tier,
                activated_at = COALESCE(public.tenant_modules.activated_at, EXCLUDED.activated_at),
                expires_at = EXCLUDED.expires_at, grace_until = EXCLUDED.grace_until,
                disabled_at = CASE
                    WHEN EXCLUDED.status <> 'DISABLED' THEN NULL
                    WHEN public.tenant_modules.status = 'DISABLED'
                        THEN COALESCE(public.tenant_modules.disabled_at, EXCLUDED.disabled_at)
                    ELSE EXCLUDED.disabled_at END
            WHERE public.tenant_modules.origin = 'PLAN'
            """;

    static final String LIVE_TENANTS_SQL = """
            SELECT t.id, t.code, t.schema_name,
                   EXISTS (SELECT 1 FROM pg_catalog.pg_namespace n WHERE n.nspname = t.schema_name) AS schema_present
            FROM public.tenants t WHERE t.status <> 'DELETED' ORDER BY t.code
            """;

    /** Story 16.8 — the live tenants whose public plan type is the given one. */
    static final String LIVE_TENANTS_ON_PLAN_SQL = """
            SELECT t.id, t.code, t.schema_name,
                   EXISTS (SELECT 1 FROM pg_catalog.pg_namespace n WHERE n.nspname = t.schema_name) AS schema_present
            FROM public.tenants t WHERE t.status <> 'DELETED' AND t.plan_type = ? ORDER BY t.code
            """;

    private final JdbcTemplate jdbc;

    public JdbcPlanModuleRowStore(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @Override
    public Plan readPlan(UUID tenantId, String tenantSchema) {
        String schema = TenantSchema.validate(tenantSchema);
        try {
            Boolean present = jdbc.queryForObject(SUBSCRIPTIONS_PRESENT_SQL, Boolean.class,
                    "\"" + schema + "\".subscriptions");
            if (!Boolean.TRUE.equals(present)) {
                // unlike the registry read (no table = no plan), rows are never derived without a subscription
                // table: the tenant is reported, nothing is written (Story 16.5 review)
                log.error("Plan module rows: a tenant has no subscriptions table — rows left unchanged");
                return Plan.UNREADABLE;
            }
            return jdbc.execute((ConnectionCallback<Plan>) connection ->
                    JdbcTenantModuleStateStore.readPlan(connection, schema, tenantId, true));
        } catch (RuntimeException e) {
            log.error("Plan module rows: plan of a tenant unreadable ({}) — rows left unchanged",
                    e.getClass().getSimpleName());
            return Plan.UNREADABLE;
        }
    }

    @Override
    public Map<String, StoredRow> findRows(UUID tenantId) {
        Map<String, StoredRow> rows = new LinkedHashMap<>();
        jdbc.query(FIND_ROWS_SQL, rs -> {
            String moduleId = rs.getString("module_id");
            Origin origin;
            try {
                origin = Origin.valueOf(rs.getString("origin"));
            } catch (IllegalArgumentException | NullPointerException e) {
                origin = Origin.ADMIN; // the column CHECK makes this unreachable; never overwrite an unknown row
            }
            TenantModuleStatus status = TenantModuleStatus.fromCode(rs.getString("status"))
                    .orElse(TenantModuleStatus.DISABLED);
            rows.put(moduleId, new StoredRow(origin, status, rs.getString("tier"),
                    instant(rs.getObject("expires_at", OffsetDateTime.class)),
                    instant(rs.getObject("grace_until", OffsetDateTime.class)),
                    rs.getString("provisioned_version")));
        }, tenantId);
        return rows;
    }

    @Override
    public boolean upsertPlanRow(UUID tenantId, String moduleId, TenantModuleStatus status, String tier,
                                 Instant expiresAt, Instant graceUntil, Instant now) {
        boolean active = status == TenantModuleStatus.TRIAL || status == TenantModuleStatus.ACTIVE;
        return jdbc.update(UPSERT_PLAN_ROW_SQL, tenantId, moduleId, status.name(), tier,
                active ? offset(now) : null, offset(expiresAt), offset(graceUntil),
                status == TenantModuleStatus.DISABLED ? offset(now) : null) > 0;
    }

    @Override
    public List<LiveTenant> liveTenants() {
        return jdbc.query(LIVE_TENANTS_SQL, (rs, i) -> new LiveTenant(rs.getObject("id", UUID.class),
                rs.getString("code"), rs.getString("schema_name"), rs.getBoolean("schema_present")));
    }

    @Override
    public List<LiveTenant> liveTenants(String planType) {
        return jdbc.query(LIVE_TENANTS_ON_PLAN_SQL, (rs, i) -> new LiveTenant(rs.getObject("id", UUID.class),
                rs.getString("code"), rs.getString("schema_name"), rs.getBoolean("schema_present")), planType);
    }

    private static Instant instant(OffsetDateTime value) {
        return value != null ? value.toInstant() : null;
    }

    private static OffsetDateTime offset(Instant value) {
        return value != null ? value.atOffset(ZoneOffset.UTC) : null;
    }
}
