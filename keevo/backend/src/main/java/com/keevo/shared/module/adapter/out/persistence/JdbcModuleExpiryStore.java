package com.keevo.shared.module.adapter.out.persistence;

import com.keevo.shared.module.TenantModuleStatus;
import com.keevo.shared.module.application.port.ModuleExpiryStore;
import com.keevo.shared.module.application.port.PlanModuleRowStore.Origin;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * JDBC adapter of {@link ModuleExpiryStore} on {@code public.tenant_modules} (Story 16.1). Public schema only, bind
 * parameters only; statements join the caller's transaction ({@link JdbcTemplate} on the shared {@link DataSource}).
 * Only {@code PLAN} / {@code ADMIN} rows are ever read or written ({@code SYSTEM} free rows never), and no row is
 * ever deleted.
 */
@Component
@DependsOnDatabaseInitialization
public class JdbcModuleExpiryStore implements ModuleExpiryStore {

    static final String DUE_ROWS_SQL = """
            SELECT module_id, origin, status, expires_at, grace_until
            FROM public.tenant_modules
            WHERE tenant_id = ? AND origin IN ('PLAN', 'ADMIN')
              AND ((status IN ('TRIAL', 'ACTIVE') AND expires_at IS NOT NULL AND expires_at <= ?)
                   OR (status = 'EXPIRED' AND (grace_until IS NULL OR grace_until <= ?)))
            ORDER BY module_id
            FOR UPDATE
            """;

    static final String MARK_EXPIRED_SQL = """
            UPDATE public.tenant_modules SET status = 'EXPIRED'
            WHERE tenant_id = ? AND module_id = ? AND origin IN ('PLAN', 'ADMIN') AND status IN ('TRIAL', 'ACTIVE')
            """;

    static final String MARK_DISABLED_SQL = """
            UPDATE public.tenant_modules SET status = 'DISABLED', disabled_at = ?
            WHERE tenant_id = ? AND module_id = ? AND origin IN ('PLAN', 'ADMIN')
              AND status IN ('TRIAL', 'ACTIVE', 'EXPIRED')
            """;

    private final JdbcTemplate jdbc;

    public JdbcModuleExpiryStore(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @Override
    public List<DueRow> lockDueRows(UUID tenantId, Instant now) {
        OffsetDateTime at = now.atOffset(ZoneOffset.UTC);
        return jdbc.query(DUE_ROWS_SQL, (rs, i) -> new DueRow(rs.getString("module_id"),
                Origin.valueOf(rs.getString("origin")),
                TenantModuleStatus.fromCode(rs.getString("status")).orElseThrow(),
                instant(rs.getObject("expires_at", OffsetDateTime.class)),
                instant(rs.getObject("grace_until", OffsetDateTime.class))), tenantId, at, at);
    }

    @Override
    public boolean markExpired(UUID tenantId, String moduleId) {
        return jdbc.update(MARK_EXPIRED_SQL, tenantId, moduleId) > 0;
    }

    @Override
    public boolean markDisabled(UUID tenantId, String moduleId, Instant now) {
        return jdbc.update(MARK_DISABLED_SQL, now.atOffset(ZoneOffset.UTC), tenantId, moduleId) > 0;
    }

    private static Instant instant(OffsetDateTime value) {
        return value != null ? value.toInstant() : null;
    }
}
