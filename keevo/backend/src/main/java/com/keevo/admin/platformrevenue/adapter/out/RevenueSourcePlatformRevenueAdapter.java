package com.keevo.admin.platformrevenue.adapter.out;

import com.keevo.admin.crosstenant.CrossTenantSql;
import com.keevo.admin.crosstenant.TenantBranch;
import com.keevo.admin.platformrevenue.domain.port.out.PlatformRevenuePort;
import com.keevo.reporting.revenue.adapter.out.persistence.RevenueRelations;
import com.keevo.shared.infrastructure.persistence.TenantSchema;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Story 20.13 — the platform revenue of the super-admin API, read from the 20.9 revenue relations
 * ({@link RevenueRelations}, the SQL written once) over every tenant schema in ONE statement. This is the only class
 * of {@code admin} that touches reporting: when 20-14 moves the source to {@code shared.capability.revenue}, only its
 * import changes.
 *
 * <p>The relations are unqualified (they normally run on the tenant search_path); {@link #qualify} prefixes each of
 * their tables with the branch's schema. Branches: tenants holding {@code sales}; invoice / credit-note legs only for
 * schemas whose catalogue holds the invoicing tables at the version the SQL reads ({@code invoice_items.product_id},
 * the predicate of {@code ProvisionedRevenueSource}). Each branch aggregates its own rows (window pushed into every
 * {@code UNION ALL} leg, on the 20.9 indexes), the outer query adds the branches up.
 *
 * <p>Cost: 2 catalogue queries (O(T)) + 1 statement, whatever the number of tenants T.
 */
@Component
public class RevenueSourcePlatformRevenueAdapter implements PlatformRevenuePort {

    static final String ZONE = "Africa/Douala";

    /** The tables the revenue relations read (unqualified in {@link RevenueRelations}). */
    static final Set<String> RELATION_TABLES =
            Set.of("sales", "sale_items", "invoices", "invoice_items", "credit_notes", "credit_note_items");

    private static final Pattern TABLE_REFERENCE = Pattern.compile(
            "\\b(FROM|JOIN)\\s+(" + String.join("|", RELATION_TABLES) + ")\\b");
    /** Any table reference: FROM / JOIN followed by an identifier (a derived table starts with a parenthesis). */
    private static final Pattern ANY_REFERENCE = Pattern.compile("\\b(?:FROM|JOIN)\\s+([A-Za-z_][\\w.]*)");

    private final JdbcTemplate jdbc;
    private final CrossTenantSql crossTenant;

    public RevenueSourcePlatformRevenueAdapter(JdbcTemplate jdbc, CrossTenantSql crossTenant) {
        this.jdbc = jdbc;
        this.crossTenant = crossTenant;
    }

    @Override
    public Map<String, Totals> totals(List<TenantBranch> tenants, Instant from, Instant to, Bucket bucket) {
        Set<String> wanted = new HashSet<>();
        tenants.forEach(t -> wanted.add(t.id()));
        List<TenantBranch> branches = crossTenant.tenantsWith(null, "sales",
                        List.of("status", "occurred_at", "total_amount", "employee_id")).stream()
                .filter(t -> wanted.contains(t.id())).toList();
        if (branches.isEmpty()) {
            return Map.of();
        }
        Set<String> withInvoicing = new HashSet<>();
        crossTenant.tenantsWith(null, "invoice_items", List.of("product_id")).forEach(t -> withInvoicing.add(t.id()));

        List<Object> params = new ArrayList<>();
        String union = crossTenant.unionAll(branches, tenant -> {
            String documents = qualify(RevenueRelations.documents(true, withInvoicing.contains(tenant.id())),
                    tenant.schemaName());
            StringBuilder where = new StringBuilder("TRUE");
            if (from != null) {
                where.append(" AND d.occurred_at >= ?");
                params.add(Timestamp.from(from));
            }
            if (to != null) {
                where.append(" AND d.occurred_at < ?");
                params.add(Timestamp.from(to));
            }
            return "SELECT " + bucketExpression(bucket, tenant) + " AS bucket, "
                    + "COALESCE(SUM(d.total_amount), 0) AS revenue, COALESCE(SUM(d.doc_count), 0) AS documents "
                    + "FROM " + documents + " d WHERE " + where + " GROUP BY 1";
        });
        String sql = "SELECT u.bucket, SUM(u.revenue) AS revenue, SUM(u.documents) AS documents FROM (" + union
                + ") u WHERE u.bucket IS NOT NULL GROUP BY u.bucket ORDER BY u.bucket";
        Map<String, Totals> totals = new LinkedHashMap<>();
        jdbc.query(sql, rs -> {
            totals.put(rs.getString("bucket"), new Totals(rs.getLong("revenue"), rs.getLong("documents")));
        }, params.toArray());
        return totals;
    }

    private static String bucketExpression(Bucket bucket, TenantBranch tenant) {
        return switch (bucket) {
            case TENANT -> CrossTenantSql.tenantLiteral(tenant) + "::text";
            case DAY -> "to_char(d.occurred_at AT TIME ZONE '" + ZONE + "', 'YYYY-MM-DD')";
            case MONTH -> "to_char(d.occurred_at AT TIME ZONE '" + ZONE + "', 'YYYY-MM')";
        };
    }

    /**
     * The relation with each of its tables prefixed by {@code schemaName}; fails when a table reference of the
     * relation is not one this adapter knows (a table added to the 20.9 relations must be added here).
     */
    static String qualify(String relation, String schemaName) {
        String schema = TenantSchema.validate(schemaName);
        String qualified = TABLE_REFERENCE.matcher(relation)
                .replaceAll(m -> Matcher.quoteReplacement(m.group(1) + " " + schema + "." + m.group(2)));
        Matcher any = ANY_REFERENCE.matcher(qualified);
        while (any.find()) {
            if (!any.group(1).startsWith(schema + ".")) {
                throw new IllegalStateException("revenue relation reads an unqualified table: " + any.group(1));
            }
        }
        return qualified;
    }
}
