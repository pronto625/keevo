package com.keevo.shared.module.adapter.out.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.shared.module.LoadedModuleManifest;
import com.keevo.shared.module.ModuleManifest;
import com.keevo.shared.module.application.port.ModuleDefinitionPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JDBC adapter of {@link ModuleDefinitionPort} on {@code public.module_definitions} (Story 13.1). Always
 * schema-qualified: the tenant connection provider switches the search_path of pooled connections.
 *
 * <p>The upsert is a single statement, safe when several instances boot at once: the update only fires when a
 * seeded column differs ({@code IS DISTINCT FROM}, JSONB compared semantically), so an identical manifest set
 * changes no row; {@code installed_at} is never part of the update. The update is also skipped when the
 * incoming version is older than the stored one (numeric comparison of MAJOR.MINOR.PATCH — the column CHECK
 * guarantees the format), so an older build (rollback, rolling deploy) never downgrades the catalogue. Joins the
 * caller's transaction (the seeder runs the whole seed in one).
 */
@Component
public class JdbcModuleDefinitionStore implements ModuleDefinitionPort {

    static final String UPSERT_SQL = """
            INSERT INTO public.module_definitions AS d
                (id, version, display_name, category, requires, enhances, is_free, manifest)
            VALUES (?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?::jsonb)
            ON CONFLICT (id) DO UPDATE SET
                version = EXCLUDED.version, display_name = EXCLUDED.display_name, category = EXCLUDED.category,
                requires = EXCLUDED.requires, enhances = EXCLUDED.enhances, is_free = EXCLUDED.is_free,
                manifest = EXCLUDED.manifest
            WHERE (d.version, d.display_name, d.category, d.requires, d.enhances, d.is_free, d.manifest)
                IS DISTINCT FROM (EXCLUDED.version, EXCLUDED.display_name, EXCLUDED.category, EXCLUDED.requires,
                                  EXCLUDED.enhances, EXCLUDED.is_free, EXCLUDED.manifest)
              AND string_to_array(EXCLUDED.version, '.')::int[] >= string_to_array(d.version, '.')::int[]
            RETURNING (xmax = 0) AS inserted
            """;

    static final String FIND_VERSIONS_SQL = "SELECT id, version FROM public.module_definitions ORDER BY id";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final JdbcTemplate jdbc;

    public JdbcModuleDefinitionStore(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @Override
    public UpsertOutcome upsert(LoadedModuleManifest loaded) {
        ModuleManifest m = loaded.manifest();
        List<Boolean> rows = jdbc.query(UPSERT_SQL, (rs, i) -> rs.getBoolean("inserted"),
                m.id(), m.version(), m.displayName(), m.category().code(), toJson(m.requires()),
                toJson(m.enhances()), m.free(), loaded.json());
        if (rows.isEmpty()) {
            return UpsertOutcome.UNCHANGED;
        }
        return rows.get(0) ? UpsertOutcome.INSERTED : UpsertOutcome.UPDATED;
    }

    @Override
    public Map<String, String> findVersions() {
        Map<String, String> versions = new LinkedHashMap<>();
        jdbc.query(FIND_VERSIONS_SQL, rs -> {
            versions.put(rs.getString("id"), rs.getString("version"));
        });
        return versions;
    }

    private static String toJson(List<String> values) {
        try {
            return JSON.writeValueAsString(values);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialise " + values, e);
        }
    }
}
