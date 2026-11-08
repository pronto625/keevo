package com.keevo.admin.platformrevenue.adapter.out;

import com.keevo.admin.crosstenant.CrossTenantSql;
import com.keevo.admin.crosstenant.TenantBranch;
import com.keevo.admin.crosstenant.TenantTable;
import com.keevo.admin.platformrevenue.domain.port.out.PlatformRevenuePort;
import com.keevo.reporting.revenue.adapter.out.persistence.RevenueRelations;
import com.keevo.shared.infrastructure.persistence.TenantSchema;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Story 20.13 — the platform revenue of the super-admin API, read from the 20.9 revenue relations
 * ({@link RevenueRelations}, the SQL written once) over every tenant schema, chunked ({@link CrossTenantSql#query}).
 * This is the only class of {@code admin} that touches reporting. 20-14 moved the port to
 * {@code shared.capability.revenue}, but the SQL ({@link RevenueRelations}) stays in reporting: a tenant-bound port
 * cannot hand the relations of other schemas, so this adapter reads the SQL itself (named interface
 * {@code reporting :: revenue}).
 *
 * <p>The relations are unqualified (they normally run on the tenant search_path); {@link #qualify} prefixes each of
 * their tables with the branch's schema. Branches: tenants holding {@code sales} OR the invoicing tables at the version
 * the SQL reads (a documents-only tenant still counts); {@code documents(hasSales, hasInvoicing)} per tenant. The
 * revenue instant is {@code COALESCE(occurred_at, created_at)} (legacy sales without {@code occurred_at} keep their
 * creation time, as in 9-4 / 9-7), so the all-time total equals the sum of the months. Each branch aggregates its own
 * rows, each chunk adds its branches up, Java adds the chunks up.
 *
 * <p>Cost: the table presence of the tenants (one catalogue query, or none when the caller passes resolved branches)
 * + one statement per chunk of tenants.
 */
@Component
public class RevenueSourcePlatformRevenueAdapter implements PlatformRevenuePort {

    static final String ZONE = "Africa/Douala";

    /** The tables the revenue relations read (unqualified in {@link RevenueRelations}). */
    static final Set<String> RELATION_TABLES =
            Set.of("sales", "sale_items", "invoices", "invoice_items", "credit_notes", "credit_note_items",
                    "invoicing_settings");

    private static final Pattern TABLE_REFERENCE = Pattern.compile(
            "\\b(FROM|JOIN)\\s+(" + String.join("|", RELATION_TABLES) + ")\\b", Pattern.CASE_INSENSITIVE);
    /** Any table reference: FROM / JOIN followed by an identifier (a derived table starts with a parenthesis). */
    private static final Pattern ANY_REFERENCE =
            Pattern.compile("\\b(?:FROM|JOIN)\\s+([A-Za-z_][\\w.]*)", Pattern.CASE_INSENSITIVE);
    private static final Pattern LATERAL = Pattern.compile("\\bLATERAL\\b", Pattern.CASE_INSENSITIVE);
    /** {@code FROM a [AS] x, b} — an implicit (comma) join the qualifier would not see. */
    private static final Pattern COMMA_JOIN = Pattern.compile(
            "\\b(?:FROM|JOIN)\\s+[A-Za-z_][\\w.]*(?:\\s+(?:AS\\s+)?(?!WHERE\\b|ON\\b|JOIN\\b|LEFT\\b|INNER\\b|GROUP\\b"
            + "|ORDER\\b|UNION\\b)[A-Za-z_]\\w*)?\\s*,", Pattern.CASE_INSENSITIVE);
    private static final String REVENUE_TIME = "COALESCE(d.occurred_at, d.created_at)";

    private final CrossTenantSql crossTenant;

    public RevenueSourcePlatformRevenueAdapter(CrossTenantSql crossTenant) {
        this.crossTenant = crossTenant;
    }

    @Override
    public Map<String, Totals> totals(List<TenantBranch> tenants, Instant from, Instant to, Bucket bucket) {
        List<TenantBranch> branches = crossTenant.withPresence(tenants).stream()
                .filter(t -> t.has(TenantTable.SALES) || t.has(TenantTable.INVOICING)).toList();
        Map<String, Totals> totals = new TreeMap<>();
        if (branches.isEmpty()) {
            return totals;
        }
        List<Map.Entry<String, Totals>> rows = crossTenant.query(branches, (tenant, params) -> {
            String documents = qualify(RevenueRelations.documents(tenant.has(TenantTable.SALES),
                    tenant.has(TenantTable.INVOICING), tenant.has(TenantTable.INVOICE_STORE)), tenant.schemaName());
            StringBuilder where = new StringBuilder("TRUE");
            if (from != null) {
                where.append(" AND ").append(REVENUE_TIME).append(" >= ?");
                params.add(Timestamp.from(from));
            }
            if (to != null) {
                where.append(" AND ").append(REVENUE_TIME).append(" < ?");
                params.add(Timestamp.from(to));
            }
            return "SELECT " + bucketExpression(bucket, tenant) + " AS bucket, "
                    + "COALESCE(SUM(d.total_amount), 0) AS revenue, COALESCE(SUM(d.doc_count), 0) AS documents "
                    + "FROM " + documents + " d WHERE " + where + " GROUP BY 1";
        }, union -> "SELECT u.bucket, SUM(u.revenue) AS revenue, SUM(u.documents) AS documents FROM (" + union
                + ") u WHERE u.bucket IS NOT NULL GROUP BY u.bucket",
                (rs, n) -> Map.entry(rs.getString("bucket"), new Totals(rs.getLong("revenue"), rs.getLong("documents"))));
        for (Map.Entry<String, Totals> row : rows) {
            totals.merge(row.getKey(), row.getValue(), Totals::plus);
        }
        return new LinkedHashMap<>(totals);
    }

    private static String bucketExpression(Bucket bucket, TenantBranch tenant) {
        return switch (bucket) {
            case TENANT -> CrossTenantSql.tenantLiteral(tenant) + "::text";
            case DAY -> "to_char(" + REVENUE_TIME + " AT TIME ZONE '" + ZONE + "', 'YYYY-MM-DD')";
            case MONTH -> "to_char(" + REVENUE_TIME + " AT TIME ZONE '" + ZONE + "', 'YYYY-MM')";
            case SELLER -> "CAST(d.employee_id AS text)";
        };
    }

    /**
     * The relation with each of its tables prefixed by {@code schemaName} (keywords in any case). Refuses a relation
     * it cannot qualify safely: a table reference it does not know (a table added to the 20.9 relations must be added
     * here), a {@code LATERAL} subquery or an implicit comma join.
     */
    static String qualify(String relation, String schemaName) {
        String schema = TenantSchema.validate(schemaName);
        if (LATERAL.matcher(relation).find()) {
            throw new IllegalStateException("revenue relation uses LATERAL: cannot be schema-qualified safely");
        }
        if (COMMA_JOIN.matcher(relation).find()) {
            throw new IllegalStateException("revenue relation uses a comma join: cannot be schema-qualified safely");
        }
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
