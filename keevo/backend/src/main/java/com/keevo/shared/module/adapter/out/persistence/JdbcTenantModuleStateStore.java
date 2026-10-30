package com.keevo.shared.module.adapter.out.persistence;

import com.keevo.shared.infrastructure.persistence.TenantSchema;
import com.keevo.shared.module.TenantModuleStatus;
import com.keevo.shared.module.application.port.TenantModuleProvisioningPort;
import com.keevo.shared.module.application.port.TenantModuleStatePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * JDBC adapter of {@link TenantModuleStatePort} on {@code public.tenant_modules} (Story 13.2). One query per call:
 * the tenant is resolved from its schema name ({@code public.tenants.schema_name}, UNIQUE) and its rows are
 * left-joined (status, {@code expires_at}, {@code grace_until}, {@code tier} — Story 13.5), so an unknown tenant (no row at all) is told apart from a tenant without module rows (one row with
 * a null module id); the tenant's subscription plan is then read apart (Story 13.10a, {@link #readPlan}). A {@code DELETED} tenant counts as unknown (like every other public reader).
 * Schema-qualified, bind parameters only.
 *
 * <p>Story 13.4: also the write side used by the module provisioner ({@link TenantModuleProvisioningPort}):
 * {@code provisioned_version} updates, the {@code ACTIVE}/{@code FREE} row of a free module provisioned without a
 * row, and the list of live tenants.
 */
@Component
@DependsOnDatabaseInitialization
public class JdbcTenantModuleStateStore implements TenantModuleStatePort, TenantModuleProvisioningPort {

    private static final Logger log = LoggerFactory.getLogger(JdbcTenantModuleStateStore.class);

    static final String FIND_STATUSES_SQL = """
            SELECT t.id AS tenant_id, t.code AS tenant_code,
                   tm.module_id, tm.status, tm.expires_at, tm.grace_until, tm.tier
            FROM public.tenants t
            LEFT JOIN public.tenant_modules tm ON tm.tenant_id = t.id
            WHERE t.schema_name = ? AND t.status <> 'DELETED'
            """;

    /**
     * Story 13.10a — the tenant's plan, as {@code TenantPlanQuery} reads it (oldest row; {@code id} breaks a tie on
     * {@code created_at}). {@code %s} is a schema name validated by {@link TenantSchema}, double-quoted.
     */
    static final String FIND_PLAN_SQL = "SELECT plan_type, status, expires_at FROM \"%s\".subscriptions "
            + "ORDER BY created_at ASC, id ASC LIMIT 1";

    /** Story 13.10a — the tenant's latest plan change (an {@code EXPIRY} downgrade anchors the grace). */
    static final String LAST_PLAN_EVENT_SQL = "SELECT cause, occurred_at FROM public.tenant_plan_events "
            + "WHERE tenant_id = ? ORDER BY occurred_at DESC, id DESC LIMIT 1";

    /** Seconds a plan read may take before it is abandoned ({@link Plan#UNREADABLE}). */
    static final int PLAN_QUERY_TIMEOUT_SECONDS = 3;

    /** PostgreSQL {@code undefined_table}: the tenant has no {@code subscriptions} table (no plan). */
    private static final String UNDEFINED_TABLE = "42P01";

    static final String UPDATE_PROVISIONED_VERSION_SQL = """
            UPDATE public.tenant_modules tm SET provisioned_version = ?
            FROM public.tenants t
            WHERE tm.tenant_id = t.id AND t.schema_name = ? AND t.status <> 'DELETED' AND tm.module_id = ?
              AND (tm.provisioned_version IS NULL
                   OR string_to_array(tm.provisioned_version, '.')::numeric[] <= string_to_array(?, '.')::numeric[])
            """;

    static final String INSERT_ACTIVE_FREE_SQL = """
            INSERT INTO public.tenant_modules (tenant_id, module_id, status, tier, activated_at, provisioned_version)
            SELECT t.id, ?, 'ACTIVE', 'FREE', now(), ?
            FROM public.tenants t
            WHERE t.schema_name = ? AND t.status <> 'DELETED'
            ON CONFLICT (tenant_id, module_id) DO UPDATE SET provisioned_version = EXCLUDED.provisioned_version
            WHERE public.tenant_modules.provisioned_version IS NULL
               OR string_to_array(public.tenant_modules.provisioned_version, '.')::numeric[]
                  <= string_to_array(EXCLUDED.provisioned_version, '.')::numeric[]
            """;

    static final String LIVE_TENANTS_SQL = """
            SELECT t.code, t.schema_name,
                   EXISTS (SELECT 1 FROM pg_catalog.pg_namespace n WHERE n.nspname = t.schema_name) AS schema_present
            FROM public.tenants t WHERE t.status <> 'DELETED' ORDER BY t.code
            """;

    private final JdbcTemplate jdbc;
    private final DataSource dataSource;

    public JdbcTenantModuleStateStore(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.dataSource = dataSource;
    }

    @Override
    public Optional<Map<String, Row>> findRows(String tenantSchema) {
        return findTenantRows(tenantSchema).map(TenantRows::rows);
    }

    @Override
    public Optional<TenantRows> findTenantRows(String tenantSchema) {
        String schema = TenantSchema.validate(tenantSchema);
        Map<String, Row> rows = new LinkedHashMap<>();
        boolean[] tenantFound = {false};
        Object[] tenant = new Object[2];
        jdbc.query(FIND_STATUSES_SQL, rs -> {
            if (!tenantFound[0]) {
                tenant[0] = rs.getObject("tenant_id", UUID.class);
                tenant[1] = rs.getString("tenant_code");
            }
            tenantFound[0] = true;
            String moduleId = rs.getString("module_id");
            if (moduleId == null) {
                return;
            }
            String code = rs.getString("status");
            TenantModuleStatus status = TenantModuleStatus.fromCode(code).orElseGet(() -> {
                // the column CHECK makes this unreachable; never let an unexpected value activate a module
                log.warn("Module registry: unexpected tenant_modules status for module '{}' — treated as DISABLED",
                        moduleId);
                return TenantModuleStatus.DISABLED;
            });
            // V29: expires_at / grace_until are TIMESTAMPTZ — read as OffsetDateTime (an absolute instant, never
            // reinterpreted in the JVM default zone like java.sql.Timestamp would be)
            rows.put(moduleId, new Row(status, instant(rs.getObject("expires_at", OffsetDateTime.class)),
                    instant(rs.getObject("grace_until", OffsetDateTime.class)), rs.getString("tier")));
        }, schema);
        if (!tenantFound[0]) {
            return Optional.empty();
        }
        return Optional.of(new TenantRows(rows, readPlan(schema, (UUID) tenant[0], (String) tenant[1])));
    }

    /**
     * Story 13.10a review — the plan is read <b>apart</b> from the module rows, on a dedicated connection (outside
     * the caller's transaction, so a failure can never poison it), with a short timeout: a fault in a tenant's
     * {@code subscriptions} (missing column, lock, unexpected value) only affects the paid modules, which are then
     * granted for this request (ERROR logged with the tenant code). A missing {@code subscriptions} table means no
     * plan ({@link Plan#NONE}).
     */
    Plan readPlan(String schema, UUID tenantId, String tenantCode) {
        try (Connection connection = dataSource.getConnection()) {
            Plan plan;
            try (PreparedStatement ps = connection.prepareStatement(FIND_PLAN_SQL.formatted(TenantSchema.validate(schema)))) {
                ps.setQueryTimeout(PLAN_QUERY_TIMEOUT_SECONDS);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return Plan.NONE;
                    }
                    plan = new Plan(rs.getString("plan_type"), rs.getString("status"),
                            instant(rs.getObject("expires_at", OffsetDateTime.class)));
                }
            } catch (SQLException e) {
                if (UNDEFINED_TABLE.equals(e.getSQLState())) {
                    return Plan.NONE;
                }
                throw e;
            }
            Instant expiryDowngradeAt = null;
            try (PreparedStatement ps = connection.prepareStatement(LAST_PLAN_EVENT_SQL)) {
                ps.setQueryTimeout(PLAN_QUERY_TIMEOUT_SECONDS);
                ps.setObject(1, tenantId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next() && "EXPIRY".equals(rs.getString("cause"))) {
                        expiryDowngradeAt = instant(rs.getObject("occurred_at", OffsetDateTime.class));
                    }
                }
            }
            return new Plan(plan.planType(), plan.status(), plan.expiresAt(), expiryDowngradeAt, false);
        } catch (SQLException | RuntimeException e) {
            log.error("Module registry: plan of tenant {} unreadable ({}) — paid modules granted for this request",
                    tenantCode, e.getClass().getSimpleName());
            return Plan.UNREADABLE;
        }
    }

    private static Instant instant(OffsetDateTime value) {
        return value != null ? value.toInstant() : null;
    }

    @Override
    public int updateProvisionedVersion(String tenantSchema, String moduleId, String version) {
        String schema = TenantSchema.validate(tenantSchema);
        return jdbc.update(UPDATE_PROVISIONED_VERSION_SQL, version, schema, moduleId, version);
    }

    @Override
    public int insertActiveFree(String tenantSchema, String moduleId, String version) {
        String schema = TenantSchema.validate(tenantSchema);
        return jdbc.update(INSERT_ACTIVE_FREE_SQL, moduleId, version, schema);
    }

    @Override
    public List<LiveTenant> liveTenants() {
        return jdbc.query(LIVE_TENANTS_SQL,
                (rs, i) -> new LiveTenant(rs.getString("code"), rs.getString("schema_name"),
                        rs.getBoolean("schema_present")));
    }
}
