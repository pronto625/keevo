package com.keevo.shared.module.adapter.out.persistence;

import com.keevo.shared.infrastructure.persistence.TenantSchema;
import com.keevo.shared.module.application.port.ModuleActivationRuleStore;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Story 19.13 — JDBC adapter of {@link ModuleActivationRuleStore} on {@code public.module_activation_rule_sets} +
 * {@code public.module_activation_rule_modules}. Runs on the caller's transaction; no cache (onboarding and
 * super-admin only). A sector-less set is matched with {@code IS NOT DISTINCT FROM} (the key is UNIQUE NULLS NOT
 * DISTINCT).
 */
@Component
@DependsOnDatabaseInitialization
public class JdbcModuleActivationRuleStore implements ModuleActivationRuleStore {

    /** Ordered by flow ({@code POS} > {@code DOCUMENT} descending), then sector, the default (NULL) first. */
    static final String FIND_ALL_SQL = """
            SELECT s.id, s.sale_flow, s.sector_type, s.updated_at, m.module_id
            FROM public.module_activation_rule_sets s
            LEFT JOIN public.module_activation_rule_modules m ON m.rule_set_id = s.id
            ORDER BY s.sale_flow DESC, s.sector_type NULLS FIRST, m.module_id
            """;

    static final String UPSERT_SET_SQL = """
            INSERT INTO public.module_activation_rule_sets (sale_flow, sector_type, updated_at) VALUES (?, ?, ?)
            ON CONFLICT (sale_flow, sector_type) DO UPDATE SET updated_at = EXCLUDED.updated_at
            RETURNING id, (xmax = 0) AS created
            """;

    static final String DELETE_MODULES_SQL = "DELETE FROM public.module_activation_rule_modules WHERE rule_set_id = ?";

    static final String INSERT_MODULES_SQL = """
            INSERT INTO public.module_activation_rule_modules (rule_set_id, module_id)
            SELECT ?, unnest(?::varchar[]) ON CONFLICT DO NOTHING
            """;

    static final String DELETE_SET_SQL = """
            DELETE FROM public.module_activation_rule_sets
            WHERE sale_flow = ? AND sector_type IS NOT DISTINCT FROM CAST(? AS VARCHAR)
            """;

    /**
     * Resolution (most specific set wins) and the insert in one statement: the (flow, sector) set if it exists, else
     * (flow, NULL); {@code ON CONFLICT DO NOTHING} keeps an existing owner choice.
     */
    static final String APPLY_SQL = """
            INSERT INTO public.tenant_module_activation (tenant_id, module_id, activated, updated_at)
            SELECT t.id, r.module_id, false, ?
            FROM public.tenants t
            JOIN public.module_activation_rule_modules r ON r.rule_set_id = (
                SELECT s.id FROM public.module_activation_rule_sets s
                WHERE s.sale_flow = ? AND (s.sector_type IS NULL OR s.sector_type = CAST(? AS VARCHAR))
                ORDER BY s.sector_type NULLS LAST LIMIT 1)
            WHERE t.schema_name = ? AND t.status <> 'DELETED'
            ON CONFLICT (tenant_id, module_id) DO NOTHING
            """;

    private final JdbcTemplate jdbc;

    public JdbcModuleActivationRuleStore(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @Override
    public List<StoredRuleSet> findAll() {
        Map<UUID, Object[]> sets = new LinkedHashMap<>();
        jdbc.query(FIND_ALL_SQL, rs -> {
            UUID id = rs.getObject("id", UUID.class);
            Object[] set = sets.computeIfAbsent(id, k -> {
                try {
                    OffsetDateTime updated = rs.getObject("updated_at", OffsetDateTime.class);
                    return new Object[]{rs.getString("sale_flow"), rs.getString("sector_type"),
                            updated != null ? updated.toInstant() : null, new ArrayList<String>()};
                } catch (java.sql.SQLException e) {
                    throw new IllegalStateException(e);
                }
            });
            String moduleId = rs.getString("module_id");
            if (moduleId != null) {
                @SuppressWarnings("unchecked")
                List<String> modules = (List<String>) set[3];
                modules.add(moduleId);
            }
        });
        List<StoredRuleSet> result = new ArrayList<>();
        sets.forEach((id, set) -> {
            @SuppressWarnings("unchecked")
            List<String> modules = (List<String>) set[3];
            result.add(new StoredRuleSet(id, (String) set[0], (String) set[1], modules, (Instant) set[2]));
        });
        return result;
    }

    @Override
    public boolean save(String saleFlow, String sectorType, List<String> modules, Instant at) {
        Object[] saved = jdbc.queryForObject(UPSERT_SET_SQL,
                (rs, i) -> new Object[]{rs.getObject("id", UUID.class), rs.getBoolean("created")},
                saleFlow, sectorType, Timestamp.from(at));
        UUID id = (UUID) saved[0];
        jdbc.update(DELETE_MODULES_SQL, id);
        if (!modules.isEmpty()) {
            jdbc.update(con -> {
                var ps = con.prepareStatement(INSERT_MODULES_SQL);
                ps.setObject(1, id);
                ps.setArray(2, con.createArrayOf("varchar", modules.toArray()));
                return ps;
            });
        }
        return (Boolean) saved[1];
    }

    @Override
    public boolean delete(String saleFlow, String sectorType) {
        return jdbc.update(DELETE_SET_SQL, saleFlow, sectorType) > 0;
    }

    @Override
    public int applyInitial(String tenantSchema, String saleFlow, String sectorType, Instant at) {
        String schema = TenantSchema.validate(tenantSchema);
        return jdbc.update(APPLY_SQL, Timestamp.from(at), saleFlow, sectorType, schema);
    }
}
