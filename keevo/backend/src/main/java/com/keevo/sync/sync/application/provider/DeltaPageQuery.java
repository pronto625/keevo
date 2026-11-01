package com.keevo.sync.sync.application.provider;

import com.keevo.shared.application.port.sync.DeltaEntityProvider.Position;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

/**
 * Keyset-paged delta query shared by the providers: rows whose cursor expression is
 * {@code >= since}, in stable (cursor, id) order, strictly after a previous page's last row.
 * The cursor value is appended as the last selected column; row[0] must be the id.
 */
final class DeltaPageQuery {

    record Rows(List<Object[]> rows, Position next) {}

    /** Parameter names bound by the keyset query itself; a filter may not reuse them. */
    static final java.util.Set<String> RESERVED_PARAMS = java.util.Set.of("since", "afterCursor", "afterId");

    private DeltaPageQuery() {}

    /**
     * @param columns    selected columns, starting with {@code id}
     * @param table      table name
     * @param cursorExpr cursor column or expression (e.g. {@code updated_at})
     */
    static Rows fetch(EntityManager em, String columns, String table, String cursorExpr,
                      Instant since, Position after, int limit) {
        return fetch(em, columns, table, cursorExpr, since, after, limit, null, Map.of());
    }

    /**
     * Same keyset page, restricted by an extra predicate (Story 16.10 — employee store scope).
     *
     * @param filter       optional SQL predicate written by the provider (a constant, never built
     *                     from caller values), ANDed to the cursor bounds; {@code null} for none
     * @param filterParams named parameters referenced by {@code filter}, always bound; required with a
     *                     filter, empty or {@code null} without one, never a reserved name ({@link #RESERVED_PARAMS})
     * @throws IllegalArgumentException on a filter/parameters mismatch or a reserved parameter name
     */
    @SuppressWarnings("unchecked")
    static Rows fetch(EntityManager em, String columns, String table, String cursorExpr,
                      Instant since, Position after, int limit,
                      String filter, Map<String, Object> filterParams) {
        if (filter != null && filterParams == null) {
            throw new IllegalArgumentException("filter parameters required with a filter");
        }
        if (filter == null && filterParams != null && !filterParams.isEmpty()) {
            throw new IllegalArgumentException("filter parameters given without a filter");
        }
        if (filterParams != null) {
            for (String name : filterParams.keySet()) {
                if (RESERVED_PARAMS.contains(name)) {
                    throw new IllegalArgumentException("reserved parameter name: " + name);
                }
            }
        }
        // id compared as text: works for uuid and varchar ids alike, in a total order.
        String idExpr = "CAST(id AS text)";
        StringBuilder sql = new StringBuilder("SELECT ").append(columns).append(", ")
                .append(cursorExpr).append(" AS sync_cursor FROM ").append(table)
                .append(" WHERE ").append(cursorExpr).append(" >= :since");
        if (filter != null) {
            sql.append(" AND (").append(filter).append(")");
        }
        if (after != null) {
            // Row-value comparison (same order as the ORDER BY): the (cursor, CAST(id AS text))
            // sync keyset index can start its scan right after the previous page (Story 11.5).
            sql.append(" AND (").append(cursorExpr).append(", ").append(idExpr)
                    .append(") > (:afterCursor, :afterId)");
        }
        sql.append(" ORDER BY ").append(cursorExpr).append(", ").append(idExpr);

        Query query = em.createNativeQuery(sql.toString());
        query.setParameter("since", Timestamp.from(since));
        if (after != null) {
            query.setParameter("afterCursor", Timestamp.from(after.cursor()));
            query.setParameter("afterId", after.id());
        }
        if (filter != null) {
            filterParams.forEach(query::setParameter);
        }
        // One extra row tells whether another page exists.
        query.setMaxResults(limit + 1);
        List<Object[]> rows = query.getResultList();
        if (rows.size() <= limit) return new Rows(rows, null);

        List<Object[]> pageRows = rows.subList(0, limit);
        Object[] last = pageRows.get(limit - 1);
        return new Rows(pageRows, new Position(toInstant(last[last.length - 1]), String.valueOf(last[0])));
    }

    static Instant toInstant(Object value) {
        if (value instanceof Instant i) return i;
        if (value instanceof Timestamp t) return t.toInstant();
        if (value instanceof OffsetDateTime o) return o.toInstant();
        if (value instanceof ZonedDateTime z) return z.toInstant();
        if (value instanceof LocalDateTime l) return l.atZone(ZoneId.systemDefault()).toInstant();
        if (value instanceof java.util.Date d) return d.toInstant();
        throw new IllegalArgumentException("Unsupported cursor value: " + value);
    }
}
