package com.keevo.shared.module.adapter.out.persistence;

import com.keevo.shared.infrastructure.persistence.TenantSchema;
import com.keevo.shared.module.application.port.OwnerModuleActivationStore;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Story 19.13 — JDBC adapter of {@link OwnerModuleActivationStore} on {@code public.tenant_module_activation}. Runs on
 * the caller's transaction. Reads are PK-prefix lookups ({@code tenant_id}); the write is one upsert.
 */
@Component
@DependsOnDatabaseInitialization
public class JdbcOwnerModuleActivationStore implements OwnerModuleActivationStore {

    static final String FIND_TENANT_SQL =
            "SELECT id FROM public.tenants WHERE schema_name = ? AND status <> 'DELETED'";

    static final String FIND_ACTIVATIONS_SQL =
            "SELECT module_id, activated FROM public.tenant_module_activation WHERE tenant_id = ? ORDER BY module_id";

    static final String UPSERT_SQL = """
            INSERT INTO public.tenant_module_activation (tenant_id, module_id, activated, updated_at, updated_by)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (tenant_id, module_id) DO UPDATE SET activated = EXCLUDED.activated,
                updated_at = EXCLUDED.updated_at, updated_by = EXCLUDED.updated_by
            """;

    private final JdbcTemplate jdbc;

    public JdbcOwnerModuleActivationStore(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @Override
    public Optional<UUID> findTenantId(String tenantSchema) {
        String schema = TenantSchema.validate(tenantSchema);
        return jdbc.query(FIND_TENANT_SQL, (rs, i) -> rs.getObject(1, UUID.class), schema).stream().findFirst();
    }

    @Override
    public Map<String, Boolean> findActivations(UUID tenantId) {
        Map<String, Boolean> rows = new LinkedHashMap<>();
        jdbc.query(FIND_ACTIVATIONS_SQL, rs -> {
            rows.put(rs.getString("module_id"), rs.getBoolean("activated"));
        }, tenantId);
        return rows;
    }

    @Override
    public void upsert(UUID tenantId, String moduleId, boolean activated, UUID actorId, Instant at) {
        jdbc.update(UPSERT_SQL, tenantId, moduleId, activated, Timestamp.from(at), actorId);
    }
}
