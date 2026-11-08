package com.keevo.admin.crosstenant;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Story 20.13 — a keyset page of a cross-account list:
 * <pre>
 * per chunk of tenants (CrossTenantSql#query):
 *   SELECT * FROM (
 *     (SELECT 'tenant'::text AS tenant_id, cols, keys FROM kv_x… WHERE filters AND keyset ORDER BY keys LIMIT n+1)
 *     UNION ALL (… one branch per tenant of the chunk …)
 *   ) u ORDER BY keys, tenant_id LIMIT n+1
 * then the chunk pages are merged in Java on (keys, tenant_id) and cut at n+1.
 * </pre>
 * Each branch reads at most n+1 rows from the index matching its {@code ORDER BY}; each chunk returns at most n+1
 * rows; the merge sorts at most chunks·(n+1) rows. The next cursor carries the last row's typed keys and tenant.
 */
@Component
public class CrossTenantLists {

    /** The parts of one tenant's branch; {@code where} params must be appended to the list while building it. */
    public record Branch(String columns, String from, String where) {
    }

    @FunctionalInterface
    public interface BranchWriter {
        /** The branch of {@code tenant}; appends the bound values of {@code where} to {@code params}. */
        Branch write(TenantBranch tenant, List<Object> params);
    }

    private final CrossTenantSql crossTenant;

    public CrossTenantLists(CrossTenantSql crossTenant) {
        this.crossTenant = crossTenant;
    }

    public <T> CursorPage<T> page(List<TenantBranch> tenants, KeysetSort sort, String rawCursor, String filters,
                                  int limit, BranchWriter writer, RowMapper<T> mapper) {
        KeysetCursor cursor = KeysetCursor.decode(rawCursor, filters, sort);
        if (tenants.isEmpty()) {
            return new CursorPage<>(List.of(), null, false);
        }
        int fetch = limit + 1;
        List<KeysetSort.Row<T>> rows = new ArrayList<>(crossTenant.query(tenants, (tenant, params) -> {
            Branch branch = writer.write(tenant, params);
            String keyset = sort.predicate(tenant.id(), cursor, params);
            return "SELECT " + CrossTenantSql.tenantLiteral(tenant) + "::text AS tenant_id, " + branch.columns()
                    + ", " + sort.selectKeys() + " FROM " + branch.from() + " WHERE (" + branch.where() + ") AND "
                    + keyset + " ORDER BY " + sort.branchOrderBy() + " LIMIT " + fetch;
        }, union -> "SELECT * FROM (" + union + ") u ORDER BY " + sort.outerOrderBy() + " LIMIT " + fetch,
                (rs, n) -> new KeysetSort.Row<>(mapper.mapRow(rs, n), rs.getString("tenant_id"), sort.readKeys(rs))));
        rows.sort(sort.comparator());

        List<T> items = new ArrayList<>();
        for (int i = 0; i < Math.min(limit, rows.size()); i++) {
            items.add(rows.get(i).item());
        }
        if (rows.size() <= limit) {
            return new CursorPage<>(items, null, false);
        }
        KeysetSort.Row<T> last = rows.get(limit - 1);
        return new CursorPage<>(items, KeysetCursor.encode(filters, last.tenantId(), sort.format(last.keys())), true);
    }
}
