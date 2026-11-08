package com.keevo.admin.crosstenant;

import java.util.List;
import java.util.StringJoiner;

/**
 * Story 20.13 — the sort of a cross-account list: the ordered key expressions of a branch (written in the order of an
 * existing index of the table, so each branch is an index range scan) and one direction for all of them. Produces the
 * branch {@code ORDER BY}, the key select list ({@code k0, k1…}), the outer {@code ORDER BY} (keys, then tenant id as
 * the tie-break between schemas, byte order) and the per-branch keyset predicate.
 *
 * <p>Total order over the merged rows: {@code (k0, k1…, tenant_id)}. For a branch of tenant {@code t} and a cursor
 * {@code (c, ct)}, the rows after the cursor are {@code keys < c} (descending), or {@code keys <= c} when {@code t}
 * sorts before {@code ct} (equal keys of a later tenant follow the cursor row). Ascending mirrors it. The comparison is
 * a row comparison on the index columns, so it stays an index range bound.
 */
public record KeysetSort(List<Key> keys, boolean descending) {

    /** A key: its SQL expression in the branch and the SQL type its cursor value is cast to. */
    public record Key(String expression, String sqlType) {
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

    private String direction() {
        return descending ? " DESC" : " ASC";
    }
}
