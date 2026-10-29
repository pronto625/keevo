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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * JDBC adapter of {@link TenantModuleStatePort} on {@code public.tenant_modules} (Story 13.2). One query per call:
 * the tenant is resolved from its schema name ({@code public.tenants.schema_name}, UNIQUE) and its rows are
 * left-joined, so an unknown tenant (no row at all) is told apart from a tenant without module rows (one row with
 * a null module id). A {@code DELETED} tenant counts as unknown (like every other public reader).
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
            SELECT tm.module_id, tm.status
            FROM public.tenants t
            LEFT JOIN public.tenant_modules tm ON tm.tenant_id = t.id
            WHERE t.schema_name = ? AND t.status <> 'DELETED'
            """;

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

    public JdbcTenantModuleStateStore(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @Override
    public Optional<Map<String, TenantModuleStatus>> findStatuses(String tenantSchema) {
        String schema = TenantSchema.validate(tenantSchema);
        Map<String, TenantModuleStatus> statuses = new LinkedHashMap<>();
        boolean[] tenantFound = {false};
        jdbc.query(FIND_STATUSES_SQL, rs -> {
            tenantFound[0] = true;
            String moduleId = rs.getString("module_id");
            if (moduleId == null) {
                return;
            }
            String code = rs.getString("status");
            statuses.put(moduleId, TenantModuleStatus.fromCode(code).orElseGet(() -> {
                // the column CHECK makes this unreachable; never let an unexpected value activate a module
                log.warn("Module registry: unexpected tenant_modules status for module '{}' — treated as DISABLED",
                        moduleId);
                return TenantModuleStatus.DISABLED;
            }));
        }, schema);
        return tenantFound[0] ? Optional.of(Map.copyOf(statuses)) : Optional.empty();
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
