package com.keevo.shared.module.adapter.out.persistence;

import com.keevo.shared.module.application.port.BundleStore;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Story 16.2 — JDBC adapter of {@link BundleStore} on {@code public.bundle_definitions} +
 * {@code public.bundle_modules}. Runs on the caller's transaction; no cache (super-admin reads only).
 */
@Component
@DependsOnDatabaseInitialization
public class JdbcBundleStore implements BundleStore {

    static final String FIND_ALL_SQL = """
            SELECT b.id, b.name, b.description, m.module_id
            FROM public.bundle_definitions b
            LEFT JOIN public.bundle_modules m ON m.bundle_id = b.id
            ORDER BY b.name, b.id, m.position, m.module_id
            """;

    static final String FIND_SQL = """
            SELECT b.id, b.name, b.description, m.module_id
            FROM public.bundle_definitions b
            LEFT JOIN public.bundle_modules m ON m.bundle_id = b.id
            WHERE b.id = ?
            ORDER BY m.position, m.module_id
            """;

    static final String UPSERT_SQL = """
            INSERT INTO public.bundle_definitions (id, name, description, created_at, updated_at, updated_by)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name, description = EXCLUDED.description,
                updated_at = EXCLUDED.updated_at, updated_by = EXCLUDED.updated_by
            RETURNING (xmax = 0) AS created
            """;

    static final String DELETE_MODULES_SQL = "DELETE FROM public.bundle_modules WHERE bundle_id = ?";
    static final String INSERT_MODULE_SQL =
            "INSERT INTO public.bundle_modules (bundle_id, module_id, position) VALUES (?, ?, ?)";
    static final String DELETE_SQL = "DELETE FROM public.bundle_definitions WHERE id = ?";

    private final JdbcTemplate jdbc;

    public JdbcBundleStore(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @Override
    public List<StoredBundle> findAll() {
        return read(jdbc.queryForList(FIND_ALL_SQL));
    }

    @Override
    public Optional<StoredBundle> find(String bundleId) {
        return read(jdbc.queryForList(FIND_SQL, bundleId)).stream().findFirst();
    }

    @Override
    public boolean save(StoredBundle bundle, UUID actorId) {
        Timestamp now = Timestamp.from(Instant.now());
        Boolean created = jdbc.queryForObject(UPSERT_SQL, Boolean.class, bundle.id(), bundle.name(),
                bundle.description(), now, now, actorId);
        jdbc.update(DELETE_MODULES_SQL, bundle.id());
        int position = 0;
        for (String moduleId : bundle.modules()) {
            jdbc.update(INSERT_MODULE_SQL, bundle.id(), moduleId, position++);
        }
        return Boolean.TRUE.equals(created);
    }

    @Override
    public boolean delete(String bundleId) {
        return jdbc.update(DELETE_SQL, bundleId) > 0;
    }

    /** Groups the joined rows by bundle, keeping the query order. */
    private static List<StoredBundle> read(List<Map<String, Object>> rows) {
        Map<String, Object[]> heads = new LinkedHashMap<>();
        Map<String, List<String>> modules = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String id = (String) row.get("id");
            heads.putIfAbsent(id, new Object[] {row.get("name"), row.get("description")});
            List<String> list = modules.computeIfAbsent(id, k -> new ArrayList<>());
            Object moduleId = row.get("module_id");
            if (moduleId != null) {
                list.add((String) moduleId);
            }
        }
        List<StoredBundle> bundles = new ArrayList<>();
        heads.forEach((id, head) -> bundles.add(new StoredBundle(id, (String) head[0], (String) head[1],
                modules.get(id))));
        return bundles;
    }
}
