package com.keevo.admin.crosstenant;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Story 20.13 — runs a keyset page of a cross-account list as ONE statement:
 * <pre>
 * SELECT * FROM (
 *   (SELECT 'tenant'::text AS tenant_id, cols, keys FROM kv_x… WHERE filters AND keyset ORDER BY keys LIMIT n+1)
 *   UNION ALL (… one branch per tenant schema holding the table …)
 * ) u ORDER BY keys, tenant_id LIMIT n+1
 * </pre>
 * Each branch reads at most n+1 rows from the index matching its {@code ORDER BY}; the outer query merges at most
 * T·(n+1) rows. The next cursor carries the last row's keys and tenant (see {@link KeysetSort}).
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

    private final JdbcTemplate jdbc;
    private final CrossTenantSql crossTenant;

    public CrossTenantLists(JdbcTemplate jdbc, CrossTenantSql crossTenant) {
        this.jdbc = jdbc;
        this.crossTenant = crossTenant;
    }

    public <T> CursorPage<T> page(List<TenantBranch> tenants, KeysetSort sort, String rawCursor, String filters,
                                  int limit, BranchWriter writer, RowMapper<T> mapper) {
        KeysetCursor cursor = KeysetCursor.decode(rawCursor, filters, sort.size());
        if (tenants.isEmpty()) {
            return new CursorPage<>(List.of(), null, false);
        }
        int fetch = limit + 1;
        List<Object> params = new ArrayList<>();
        String union = crossTenant.unionAll(tenants, tenant -> {
            Branch branch = writer.write(tenant, params);
            String keyset = sort.predicate(tenant.id(), cursor, params);
            return "SELECT " + CrossTenantSql.tenantLiteral(tenant) + "::text AS tenant_id, " + branch.columns()
                    + ", " + sort.selectKeys() + " FROM " + branch.from() + " WHERE (" + branch.where() + ") AND "
                    + keyset + " ORDER BY " + sort.branchOrderBy() + " LIMIT " + fetch;
        });
        String sql = "SELECT * FROM (" + union + ") u ORDER BY " + sort.outerOrderBy() + " LIMIT " + fetch;

        List<T> items = new ArrayList<>();
        List<String[]> keys = new ArrayList<>();
        jdbc.query(sql, rs -> {
            items.add(mapper.mapRow(rs, items.size()));
            String[] row = new String[sort.size() + 1];
            row[0] = rs.getString("tenant_id");
            for (int i = 0; i < sort.size(); i++) {
                row[i + 1] = rs.getString("k" + i);
            }
            keys.add(row);
        }, params.toArray());

        if (items.size() <= limit) {
            return new CursorPage<>(items, null, false);
        }
        String[] last = keys.get(limit - 1);
        String next = KeysetCursor.encode(filters, last[0], List.of(last).subList(1, last.length));
        return new CursorPage<>(items.subList(0, limit), next, true);
    }
}
