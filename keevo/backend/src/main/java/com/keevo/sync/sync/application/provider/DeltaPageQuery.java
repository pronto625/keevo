package com.keevo.sync.sync.application.provider;

import com.keevo.sync.sync.domain.port.in.DeltaEntityProvider.Position;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

/**
 * Keyset-paged delta query shared by the providers: rows whose cursor expression is
 * {@code >= since}, in stable (cursor, id) order, strictly after a previous page's last row.
 * The cursor value is appended as the last selected column; row[0] must be the id.
 */
final class DeltaPageQuery {

    record Rows(List<Object[]> rows, Position next) {}

    private DeltaPageQuery() {}

    /**
     * @param columns    selected columns, starting with {@code id}
     * @param table      table name
     * @param cursorExpr cursor column or expression (e.g. {@code updated_at})
     */
    @SuppressWarnings("unchecked")
    static Rows fetch(EntityManager em, String columns, String table, String cursorExpr,
                      Instant since, Position after, int limit) {
        // id compared as text: works for uuid and varchar ids alike, in a total order.
        String idExpr = "CAST(id AS text)";
        StringBuilder sql = new StringBuilder("SELECT ").append(columns).append(", ")
                .append(cursorExpr).append(" AS sync_cursor FROM ").append(table)
                .append(" WHERE ").append(cursorExpr).append(" >= :since");
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
