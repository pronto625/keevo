package com.keevo.shared.module.adapter.out.persistence;

import com.keevo.shared.infrastructure.persistence.TenantSchema;
import com.keevo.shared.module.TenantModuleStatus;
import com.keevo.shared.module.application.port.TenantModuleLifecyclePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * JDBC adapter of {@link TenantModuleLifecyclePort} on {@code public.tenants} / {@code public.tenant_modules}
 * (Story 13.8a). Schema-qualified, bind parameters only; runs on the caller's transaction (the lifecycle service is
 * {@code @Transactional}: the JDBC statements join its connection). Never deletes a row.
 */
@Component
@DependsOnDatabaseInitialization
public class JdbcTenantModuleLifecycleStore implements TenantModuleLifecyclePort {

    private static final Logger log = LoggerFactory.getLogger(JdbcTenantModuleLifecycleStore.class);

    /** The platform tenant (KV-ADMIN) is never a lifecycle target. */
    static final UUID PLATFORM_TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000000");

    static final String FIND_TENANT_SQL = """
            SELECT t.id, t.code, t.schema_name FROM public.tenants t
            WHERE t.id = ? AND t.id <> ? AND t.status <> 'DELETED'
            """;

    static final String FIND_ROWS_SQL = """
            SELECT tm.id, tm.module_id, tm.status, tm.tier, tm.activated_at, tm.expires_at, tm.grace_until,
                   tm.disabled_at, tm.provisioned_version
            FROM public.tenants t
            JOIN public.tenant_modules tm ON tm.tenant_id = t.id
            WHERE t.schema_name = ? AND t.status <> 'DELETED'
            ORDER BY tm.module_id
            """;

    static final String UPSERT_ACTIVATED_SQL = """
            INSERT INTO public.tenant_modules (tenant_id, module_id, status, tier, activated_at, expires_at,
                                               grace_until, disabled_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, NULL)
            ON CONFLICT (tenant_id, module_id) DO UPDATE SET
                status = EXCLUDED.status, tier = EXCLUDED.tier,
                activated_at = CASE WHEN ? THEN COALESCE(public.tenant_modules.activated_at, EXCLUDED.activated_at)
                                    ELSE EXCLUDED.activated_at END,
                expires_at = EXCLUDED.expires_at, grace_until = EXCLUDED.grace_until, disabled_at = NULL
            RETURNING id
            """;

    static final String UPSERT_DISABLED_SQL = """
            INSERT INTO public.tenant_modules (tenant_id, module_id, status, tier, disabled_at)
            VALUES (?, ?, 'DISABLED', ?, ?)
            ON CONFLICT (tenant_id, module_id) DO UPDATE SET
                status = 'DISABLED', disabled_at = EXCLUDED.disabled_at
            RETURNING id
            """;

    private final JdbcTemplate jdbc;

    public JdbcTenantModuleLifecycleStore(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @Override
    public Optional<TenantRef> findTenant(UUID tenantId) {
        if (tenantId == null) {
            return Optional.empty();
        }
        List<TenantRef> found = jdbc.query(FIND_TENANT_SQL, (rs, i) -> {
            String schema = rs.getString("schema_name");
            if (!TenantSchema.isValid(schema)) {
                log.warn("Module lifecycle: tenant {} has an invalid schema name — refused", rs.getString("code"));
                return null;
            }
            return new TenantRef(rs.getObject("id", UUID.class), rs.getString("code"), schema);
        }, tenantId, PLATFORM_TENANT_ID);
        return found.isEmpty() || found.get(0) == null ? Optional.empty() : Optional.of(found.get(0));
    }

    @Override
    public Map<String, StoredRow> findStoredRows(String tenantSchema) {
        String schema = TenantSchema.validate(tenantSchema);
        Map<String, StoredRow> rows = new LinkedHashMap<>();
        jdbc.query(FIND_ROWS_SQL, rs -> {
            String moduleId = rs.getString("module_id");
            TenantModuleStatus status = TenantModuleStatus.fromCode(rs.getString("status"))
                    .orElse(TenantModuleStatus.DISABLED); // the column CHECK makes this unreachable
            rows.put(moduleId, new StoredRow(rs.getObject("id", UUID.class), status, rs.getString("tier"),
                    instant(rs.getObject("activated_at", OffsetDateTime.class)),
                    instant(rs.getObject("expires_at", OffsetDateTime.class)),
                    instant(rs.getObject("grace_until", OffsetDateTime.class)),
                    instant(rs.getObject("disabled_at", OffsetDateTime.class)),
                    rs.getString("provisioned_version")));
        }, schema);
        return Map.copyOf(rows);
    }

    @Override
    public UUID upsertActivated(UUID tenantId, String moduleId, TenantModuleStatus status, String tier,
                                Instant activatedAt, boolean keepActivatedAt, Instant expiresAt,
                                Instant graceUntil) {
        return jdbc.queryForObject(UPSERT_ACTIVATED_SQL, UUID.class, tenantId, moduleId, status.name(), tier,
                offset(activatedAt), offset(expiresAt), offset(graceUntil), keepActivatedAt);
    }

    @Override
    public UUID upsertDisabled(UUID tenantId, String moduleId, String tier, Instant disabledAt) {
        return jdbc.queryForObject(UPSERT_DISABLED_SQL, UUID.class, tenantId, moduleId, tier, offset(disabledAt));
    }

    private static Instant instant(OffsetDateTime value) {
        return value != null ? value.toInstant() : null;
    }

    private static OffsetDateTime offset(Instant value) {
        return value != null ? value.atOffset(ZoneOffset.UTC) : null;
    }
}
