package com.keevo.shared.infrastructure.export;

import com.keevo.shared.application.port.export.ExportDatasetProvider;
import com.keevo.shared.application.port.export.ExportFilter;
import com.keevo.shared.application.port.export.ExportPage;
import com.keevo.shared.infrastructure.export.KeysetCursor.KeyType;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * NativeKeysetExportDataset — reusable base of the export dataset providers (Story v1s-18-2): one
 * implementation of the keyset / tenant / bounded-memory contract of {@link ExportDatasetProvider}
 * instead of one per dataset. A provider only declares SQL fragments, its keyset and a row mapper.
 *
 * <p><b>Contract kept here</b>
 * <ul>
 *   <li>Reads only through the injected tenant-routed {@link EntityManager} (search_path), never
 *       {@code JdbcTemplate} / {@code DataSource}; one read-only transaction per {@link #count} /
 *       {@link #readPage} (Spring proxies the subclass bean).</li>
 *   <li>At most {@code limit} rows per page: {@code limit + 1} are fetched to know whether another
 *       page exists.</li>
 *   <li>Keyset strictly increasing on a unique tuple ending with a primary key:
 *       {@code (k1,…,kn) > (:k1,…,:kn) ORDER BY k1,…,kn}. Every keyset expression must be non-null
 *       (wrap joined text in {@code COALESCE(…, '')}). The keyset values are selected as trailing
 *       columns, so the row mapper only sees the {@link #selectList} columns.</li>
 *   <li>Filters: the store predicate (bound {@code :storeId}, a UUID string — write
 *       {@code x.store_id = CAST(:storeId AS uuid)}) applies only when declared; the period applies
 *       to {@link #periodExpression()} only when declared; criteria map to
 *       {@link #criteriaPredicates()} (bound {@code :crit_<key>} as text).</li>
 * </ul>
 */
public abstract class NativeKeysetExportDataset implements ExportDatasetProvider {

    /** One keyset column: a non-null SQL expression and its type. */
    public record Key(String expression, KeyType type) {
    }

    protected final EntityManager em;

    protected NativeKeysetExportDataset(EntityManager em) {
        this.em = em;
    }

    // ── declaration hooks ───────────────────────────────────────────────────────

    /** Comma-separated select list, in {@link #columns()} order (no {@code SELECT}). */
    protected abstract String selectList(ExportFilter filter);

    /** FROM clause body with its joins (no {@code FROM}); joins must never duplicate a row. */
    protected abstract String fromClause();

    /** Ordered keyset, ending with a primary key. */
    protected abstract List<Key> keyset();

    /** Map one raw row (select list columns only) to the export row, in {@link #columns()} order. */
    protected abstract List<Object> mapRow(Object[] row);

    /** Always-applied predicate, or {@code null}. */
    protected String fixedWhere() {
        return null;
    }

    /** Store predicate using {@code :storeId}, or {@code null} when rows are not store-scoped. */
    protected String storePredicate() {
        return null;
    }

    /** Event column filtered by the period, or {@code null} for a reference dataset. */
    protected String periodExpression() {
        return null;
    }

    /** Criterion key → SQL predicate using {@code :crit_<key>} (text). Empty by default. */
    protected Map<String, String> criteriaPredicates() {
        return Map.of();
    }

    /** Extra parameters referenced by {@link #selectList(ExportFilter)} only (bound on page reads). */
    protected Map<String, Object> selectParameters(ExportFilter filter) {
        return Map.of();
    }

    // ── filter metadata ─────────────────────────────────────────────────────────

    @Override
    public boolean supportsStoreFilter() {
        return storePredicate() != null;
    }

    @Override
    public boolean supportsPeriodFilter() {
        return periodExpression() != null;
    }

    @Override
    public Set<String> criteriaKeys() {
        return criteriaPredicates().keySet();
    }

    // ── contract ────────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public long count(ExportFilter filter) {
        Map<String, Object> params = new LinkedHashMap<>();
        List<String> where = where(filter, params);
        Query q = em.createNativeQuery("SELECT COUNT(*) FROM " + fromClause() + whereSql(where));
        params.forEach(q::setParameter);
        return ((Number) q.getSingleResult()).longValue();
    }

    @Override
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public ExportPage readPage(ExportFilter filter, String cursor, int limit) {
        if (limit < 1) throw new IllegalArgumentException("limit must be >= 1");
        ExportFilter f = filter != null ? filter : ExportFilter.none();
        List<Key> keys = keyset();
        List<KeyType> types = keys.stream().map(Key::type).toList();

        Map<String, Object> params = new LinkedHashMap<>();
        List<String> where = where(f, params);
        if (cursor != null) {
            List<Object> after = KeysetCursor.decode(types, cursor);
            StringBuilder left = new StringBuilder("(");
            StringBuilder right = new StringBuilder("(");
            for (int i = 0; i < keys.size(); i++) {
                if (i > 0) {
                    left.append(", ");
                    right.append(", ");
                }
                left.append(keys.get(i).expression());
                right.append("CAST(:k").append(i).append(" AS ").append(types.get(i).sqlType()).append(')');
                params.put("k" + i, KeysetCursor.toParameter(types.get(i), after.get(i)));
            }
            where.add(left.append(')') + " > " + right.append(')'));
        }
        params.putAll(selectParameters(f));

        String keyColumns = String.join(", ", keys.stream().map(Key::expression).toList());
        String sql = "SELECT " + selectList(f) + ", " + keyColumns
                + " FROM " + fromClause() + whereSql(where) + " ORDER BY " + keyColumns;
        Query q = em.createNativeQuery(sql);
        params.forEach(q::setParameter);
        q.setMaxResults(limit + 1); // one extra row tells whether another page exists
        List<Object[]> raw = q.getResultList();

        boolean more = raw.size() > limit;
        List<Object[]> pageRows = more ? raw.subList(0, limit) : raw;
        int width = pageRows.isEmpty() ? 0 : pageRows.get(0).length - keys.size();
        List<List<Object>> rows = new ArrayList<>(pageRows.size());
        for (Object[] r : pageRows) {
            rows.add(mapRow(Arrays.copyOf(r, width)));
        }
        String next = null;
        if (more) {
            Object[] last = pageRows.get(pageRows.size() - 1);
            List<Object> values = new ArrayList<>(keys.size());
            for (int i = 0; i < keys.size(); i++) {
                values.add(KeysetCursor.fromColumn(types.get(i), last[width + i]));
            }
            next = KeysetCursor.encode(types, values);
        }
        return new ExportPage(rows, next);
    }

    private List<String> where(ExportFilter filter, Map<String, Object> params) {
        ExportFilter f = filter != null ? filter : ExportFilter.none();
        List<String> clauses = new ArrayList<>();
        String fixed = fixedWhere();
        if (fixed != null) clauses.add("(" + fixed + ")");
        String store = storePredicate();
        if (store != null && f.storeId() != null) {
            clauses.add("(" + store + ")");
            params.put("storeId", f.storeId().toString());
        }
        String period = periodExpression();
        if (period != null && f.from() != null) {
            clauses.add(period + " >= CAST(:periodFrom AS timestamptz)");
            params.put("periodFrom", Timestamp.from(f.from()));
        }
        if (period != null && f.to() != null) {
            clauses.add(period + " <= CAST(:periodTo AS timestamptz)");
            params.put("periodTo", Timestamp.from(f.to()));
        }
        Map<String, String> predicates = criteriaPredicates();
        f.criteria().forEach((name, value) -> {
            String predicate = predicates.get(name);
            if (predicate == null) {
                throw new IllegalArgumentException("Unsupported criterion '" + name + "' for dataset " + key());
            }
            clauses.add("(" + predicate + ")");
            params.put("crit_" + name, value);
        });
        return clauses;
    }

    private static String whereSql(List<String> clauses) {
        return clauses.isEmpty() ? "" : " WHERE " + String.join(" AND ", clauses);
    }
}
