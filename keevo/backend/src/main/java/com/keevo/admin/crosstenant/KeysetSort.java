package com.keevo.admin.crosstenant;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.StringJoiner;
import java.util.UUID;

/**
 * Story 20.13 — the sort of a cross-account list: the ordered key expressions of a branch (written in the order of an
 * existing index of the table, so each branch is an index range scan) and one direction for all of them. Produces the
 * branch {@code ORDER BY}, the key select list ({@code k0, k1…}), the outer {@code ORDER BY} (keys, then tenant id as
 * the tie-break between schemas, byte order), the per-branch keyset predicate and the Java comparator that merges the
 * pages of several chunks in the same order.
 *
 * <p>Total order over the merged rows: {@code (k0, k1…, tenant_id)}. For a branch of tenant {@code t} and a cursor
 * {@code (c, ct)}, the rows after the cursor are {@code keys < c} (descending), or {@code keys <= c} when {@code t}
 * sorts before {@code ct} (equal keys of a later tenant follow the cursor row). Ascending mirrors it.
 *
 * <p>Key types: {@code timestamptz} (cursor value = ISO instant), {@code date} (ISO date), {@code uuid} (canonical
 * UUID), {@code text} (≤ 256 chars — used for {@code CAST(id AS text)}: UUID texts have their hyphens at the same
 * positions, so PostgreSQL's collation and Java's order agree).
 */
public record KeysetSort(List<Key> keys, boolean descending) {

    /** A key: its SQL expression in the branch and its type. */
    public record Key(String expression, String sqlType) {

        public Key {
            if (!List.of("timestamptz", "date", "uuid", "text").contains(sqlType)) {
                throw new IllegalArgumentException("unsupported keyset type " + sqlType);
            }
        }

        /** The typed value of this key in column {@code column} of the current row. */
        Comparable<?> read(ResultSet rs, String column) throws SQLException {
            return switch (sqlType) {
                case "timestamptz" -> {
                    Timestamp ts = rs.getTimestamp(column);
                    yield ts == null ? null : ts.toInstant();
                }
                case "date" -> {
                    Date d = rs.getDate(column);
                    yield d == null ? null : d.toLocalDate();
                }
                default -> rs.getString(column);
            };
        }

        /** Whether a raw cursor value is a well-formed value of this key's type. */
        boolean valid(String raw) {
            try {
                switch (sqlType) {
                    case "timestamptz" -> Instant.parse(raw);
                    case "date" -> LocalDate.parse(raw);
                    case "uuid" -> UUID.fromString(raw);
                    default -> {
                        return raw.length() <= 256;
                    }
                }
                return true;
            } catch (DateTimeParseException | IllegalArgumentException e) {
                return false;
            }
        }
    }

    /** A merged row: the mapped item, its tenant and its typed keys. */
    public record Row<T>(T item, String tenantId, List<Comparable<?>> keys) {
    }

    public KeysetSort {
        keys = List.copyOf(keys);
        if (keys.isEmpty()) {
            throw new IllegalArgumentException("a keyset sort needs at least one key");
        }
    }

    public int size() {
        return keys.size();
    }

    /** {@code expr0 AS k0, expr1 AS k1, …} */
    public String selectKeys() {
        StringJoiner j = new StringJoiner(", ");
        for (int i = 0; i < keys.size(); i++) {
            j.add(keys.get(i).expression() + " AS k" + i);
        }
        return j.toString();
    }

    /** Branch order: the key expressions in the sort direction. */
    public String branchOrderBy() {
        StringJoiner j = new StringJoiner(", ");
        for (Key key : keys) {
            j.add(key.expression() + direction());
        }
        return j.toString();
    }

    /** Outer order over the merged branches: {@code k0, k1…, tenant_id} (byte order, like Java's String order). */
    public String outerOrderBy() {
        StringJoiner j = new StringJoiner(", ");
        for (int i = 0; i < keys.size(); i++) {
            j.add("k" + i + direction());
        }
        j.add("tenant_id COLLATE \"C\"" + direction());
        return j.toString();
    }

    /**
     * The keyset predicate of the branch of {@code branchTenantId} after {@code cursor} ({@code "TRUE"} on the first
     * page); appends the bound cursor values to {@code params}.
     */
    public String predicate(String branchTenantId, KeysetCursor cursor, List<Object> params) {
        if (cursor == null) {
            return "TRUE";
        }
        int tenantOrder = branchTenantId.compareTo(cursor.tenantId());
        boolean inclusive = descending ? tenantOrder < 0 : tenantOrder > 0;
        String operator = descending ? (inclusive ? "<=" : "<") : (inclusive ? ">=" : ">");
        StringJoiner left = new StringJoiner(", ", "(", ")");
        StringJoiner right = new StringJoiner(", ", "(", ")");
        for (int i = 0; i < keys.size(); i++) {
            left.add(keys.get(i).expression());
            right.add("CAST(? AS " + keys.get(i).sqlType() + ")");
            params.add(cursor.keys().get(i));
        }
        return left + " " + operator + " " + right;
    }

    /** The typed keys {@code k0…} of the current row. */
    public List<Comparable<?>> readKeys(ResultSet rs) throws SQLException {
        List<Comparable<?>> values = new ArrayList<>(keys.size());
        for (int i = 0; i < keys.size(); i++) {
            values.add(keys.get(i).read(rs, "k" + i));
        }
        return values;
    }

    /** The cursor values of typed keys (ISO instant / date, text). */
    public List<String> format(List<Comparable<?>> values) {
        return values.stream().map(String::valueOf).toList();
    }

    /** Whether every raw cursor value is well-formed for its key. */
    public boolean valid(List<String> raw) {
        if (raw.size() != keys.size()) {
            return false;
        }
        for (int i = 0; i < keys.size(); i++) {
            if (!keys.get(i).valid(raw.get(i))) {
                return false;
            }
        }
        return true;
    }

    /** The order of the merged rows: keys, then tenant id, in the sort direction (the outer SQL order). */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public <T> Comparator<Row<T>> comparator() {
        Comparator<Row<T>> natural = (a, b) -> {
            for (int i = 0; i < keys.size(); i++) {
                int c = ((Comparable) a.keys().get(i)).compareTo(b.keys().get(i));
                if (c != 0) {
                    return c;
                }
            }
            return a.tenantId().compareTo(b.tenantId());
        };
        return descending ? natural.reversed() : natural;
    }

    private String direction() {
        return descending ? " DESC" : " ASC";
    }
}
