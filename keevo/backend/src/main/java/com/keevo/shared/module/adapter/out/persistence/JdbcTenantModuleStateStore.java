package com.keevo.shared.module.adapter.out.persistence;

import com.keevo.shared.infrastructure.persistence.TenantSchema;
import com.keevo.shared.module.TenantModuleStatus;
import com.keevo.shared.module.application.port.TenantModuleStatePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * JDBC adapter of {@link TenantModuleStatePort} on {@code public.tenant_modules} (Story 13.2). One query per call:
 * the tenant is resolved from its schema name ({@code public.tenants.schema_name}, UNIQUE) and its rows are
 * left-joined, so an unknown tenant (no row at all) is told apart from a tenant without module rows (one row with
 * a null module id). A {@code DELETED} tenant counts as unknown (like every other public reader).
 * Schema-qualified, bind parameters only; read-only.
 */
@Component
@DependsOnDatabaseInitialization
public class JdbcTenantModuleStateStore implements TenantModuleStatePort {

    private static final Logger log = LoggerFactory.getLogger(JdbcTenantModuleStateStore.class);

    static final String FIND_STATUSES_SQL = """
            SELECT tm.module_id, tm.status
            FROM public.tenants t
            LEFT JOIN public.tenant_modules tm ON tm.tenant_id = t.id
            WHERE t.schema_name = ? AND t.status <> 'DELETED'
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
}
