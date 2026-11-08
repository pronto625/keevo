package com.keevo.admin.dashboard.adapter.out.persistence;

import com.keevo.admin.AdminLabels;
import com.keevo.admin.crosstenant.CrossTenantSql;
import com.keevo.admin.crosstenant.TenantBranch;
import com.keevo.admin.crosstenant.TenantTable;
import com.keevo.admin.dashboard.domain.model.PlatformDashboardViews.ActivityItem;
import com.keevo.admin.dashboard.domain.model.PlatformDashboardViews.ActivityType;
import com.keevo.admin.dashboard.domain.model.PlatformDashboardViews.DocumentFigures;
import com.keevo.admin.dashboard.domain.model.PlatformDashboardViews.StoreFigures;
import com.keevo.admin.dashboard.domain.port.out.PlatformDashboardReadPort;
import com.keevo.shared.infrastructure.persistence.TenantSchema;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.text.NumberFormat;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.StringJoiner;
import java.util.stream.Collectors;

/**
 * Story 20.13 — platform figures of the super-admin dashboard.
 * <ul>
 *   <li>public tables ({@code users}, {@code tenants}, memberships): one aggregate query per figure;</li>
 *   <li>tenant tables ({@code stores}, {@code invoices}, {@code invoice_payments}): per-tenant branches run in chunks
 *       ({@link CrossTenantSql#query}) over the tenants holding the table — the table presence comes with the tenants
 *       ({@link #liveTenants()}, one catalogue query per request); each branch aggregates its own rows, each chunk adds
 *       its branches up, Java adds the chunks up. Invoices by month use the 20.9 partial expression index
 *       {@code idx_invoices_revenue_day}; recent invoices / quotes use {@code idx_invoices_created}; payments use the
 *       invoicing V17 partial index on {@code paid_at}; stores are small per tenant (plan limits).</li>
 * </ul>
 * Months are {@code yyyy-MM} in Africa/Douala; KV-ADMIN and deleted tenants never count; super-admins never count as
 * users.
 */
@Component
public class JdbcPlatformDashboardReader implements PlatformDashboardReadPort {

    static final String ZONE = "Africa/Douala";

    /** Counted invoice (same predicate as the 20.9 revenue source and its V14 partial index). */
    static final String COUNTED = "i.kind = 'INVOICE' AND i.status IN ('ISSUED', 'PAID') AND i.source_sale_id IS NULL "
            + "AND i.deleted_at IS NULL AND i.superseded_at IS NULL";
    /** Payment state of an invoice — the expression of {@code idx_invoices_payment_state_list} (legacy rows: NULL). */
    static final String PAYMENT_STATE =
            "COALESCE(i.payment_state, CASE i.status WHEN 'PAID' THEN 'PAID' ELSE 'UNPAID' END)";
    /** An open quote: not deleted, neither cancelled nor converted. */
    static final String OPEN_QUOTE = "i.kind = 'QUOTE' AND i.deleted_at IS NULL AND i.status NOT IN ('CANCELLED', 'CONVERTED')";
    /** The V14 index expression: civil issue date as the WAT midnight instant. */
    static final String INVOICE_DAY = "(i.issue_date::timestamp AT TIME ZONE 'Africa/Lagos')";

    private static final String SUPER_ADMIN = "SUPER_ADMIN";
    private static final String LIVE_TENANT = CrossTenantSql.LIVE_TENANT;

    private final JdbcTemplate jdbc;
    private final CrossTenantSql crossTenant;

    public JdbcPlatformDashboardReader(JdbcTemplate jdbc, CrossTenantSql crossTenant) {
        this.jdbc = jdbc;
        this.crossTenant = crossTenant;
    }

    @Override
    public List<TenantBranch> liveTenants() {
        return crossTenant.liveTenants();
    }

    @Override
    public UserCounts userCounts(Instant monthStart, Instant previousMonthStart) {
        return jdbc.queryForObject("SELECT COUNT(*) AS total, COUNT(*) FILTER (WHERE u.is_active) AS active, "
                        + "COUNT(*) FILTER (WHERE u.created_at >= ?) AS this_month, "
                        + "COUNT(*) FILTER (WHERE u.created_at >= ? AND u.created_at < ?) AS last_month "
                        + "FROM public.users u WHERE u.role <> '" + SUPER_ADMIN + "'",
                (rs, n) -> new UserCounts(rs.getLong("total"), rs.getLong("active"), rs.getLong("this_month"),
                        rs.getLong("last_month")),
                ts(monthStart), ts(previousMonthStart), ts(monthStart));
    }

    @Override
    public AccountCounts accountCounts(Instant monthStart, Instant previousMonthStart) {
        return jdbc.queryForObject("SELECT COUNT(*) AS total, COUNT(*) FILTER (WHERE t.status = 'ACTIVE') AS active, "
                        + "COUNT(*) FILTER (WHERE t.status = 'SUSPENDED') AS suspended, "
                        + "COUNT(*) FILTER (WHERE t.created_at >= ?) AS this_month, "
                        + "COUNT(*) FILTER (WHERE t.created_at >= ? AND t.created_at < ?) AS last_month "
                        + "FROM public.tenants t WHERE " + LIVE_TENANT,
                (rs, n) -> new AccountCounts(rs.getLong("total"), rs.getLong("active"), rs.getLong("suspended"),
                        rs.getLong("this_month"), rs.getLong("last_month")),
                ts(monthStart), ts(previousMonthStart), ts(monthStart));
    }

    @Override
    public StoreFigures storeFigures(List<TenantBranch> tenants, Instant monthStart) {
        List<long[]> rows = crossTenant.query(with(tenants, TenantTable.STORES), (tenant, params) -> {
            String s = TenantSchema.validate(tenant.schemaName());
            params.add(ts(monthStart));
            return "SELECT COUNT(*) AS total, COUNT(*) FILTER (WHERE st.is_active) AS active, "
                    + "COUNT(*) FILTER (WHERE st.created_at >= ?) AS created FROM " + s + ".stores st "
                    + "WHERE st.type = 'STORE'";
        }, union -> "SELECT COALESCE(SUM(total), 0) AS total, COALESCE(SUM(active), 0) AS active, "
                + "COALESCE(SUM(created), 0) AS created FROM (" + union + ") u",
                (rs, n) -> new long[]{rs.getLong("total"), rs.getLong("active"), rs.getLong("created")});
        long[] sum = sum(rows, 3);
        return new StoreFigures(sum[0], sum[1], sum[2]);
    }

    @Override
    public DocumentFigures documentFigures(List<TenantBranch> tenants) {
        List<long[]> rows = crossTenant.query(with(tenants, TenantTable.INVOICES), (tenant, params) -> {
            String s = TenantSchema.validate(tenant.schemaName());
            return "SELECT COUNT(*) FILTER (WHERE " + COUNTED + ") AS invoices, "
                    + "COUNT(*) FILTER (WHERE " + COUNTED + " AND " + PAYMENT_STATE + " = 'UNPAID') AS unpaid, "
                    + "COUNT(*) FILTER (WHERE " + COUNTED + " AND " + PAYMENT_STATE + " = 'PARTIAL') AS partial, "
                    + "COUNT(*) FILTER (WHERE " + COUNTED + " AND " + PAYMENT_STATE + " = 'PAID') AS paid, "
                    + "COUNT(*) FILTER (WHERE " + COUNTED + " AND " + PAYMENT_STATE + " = 'OVERDUE') AS overdue, "
                    + "COUNT(*) FILTER (WHERE " + OPEN_QUOTE + ") AS quotes FROM " + s + ".invoices i";
        }, union -> "SELECT COALESCE(SUM(invoices), 0) AS invoices, COALESCE(SUM(unpaid), 0) AS unpaid, "
                + "COALESCE(SUM(partial), 0) AS partial, COALESCE(SUM(paid), 0) AS paid, "
                + "COALESCE(SUM(overdue), 0) AS overdue, COALESCE(SUM(quotes), 0) AS quotes FROM (" + union + ") u",
                (rs, n) -> new long[]{rs.getLong("invoices"), rs.getLong("unpaid"), rs.getLong("partial"),
                        rs.getLong("paid"), rs.getLong("overdue"), rs.getLong("quotes")});
        long[] s = sum(rows, 6);
        return new DocumentFigures(s[0], s[1], s[2], s[3], s[4], s[5]);
    }

    @Override
    public Map<String, Long> newUsersByMonth(Instant from, Instant to) {
        return monthly(jdbc.query("SELECT to_char(u.created_at AT TIME ZONE '" + ZONE + "', 'YYYY-MM') AS month, "
                + "COUNT(*) AS n FROM public.users u WHERE u.role <> '" + SUPER_ADMIN + "' AND u.created_at >= ? "
                + "AND u.created_at < ? GROUP BY 1", MONTH_ROW, ts(from), ts(to)));
    }

    @Override
    public Map<String, Long> newAccountsByMonth(Instant from, Instant to) {
        return monthly(jdbc.query("SELECT to_char(t.created_at AT TIME ZONE '" + ZONE + "', 'YYYY-MM') AS month, "
                + "COUNT(*) AS n FROM public.tenants t WHERE " + LIVE_TENANT + " AND t.created_at >= ? "
                + "AND t.created_at < ? GROUP BY 1", MONTH_ROW, ts(from), ts(to)));
    }

    @Override
    public Map<String, Long> newStoresByMonth(List<TenantBranch> tenants, Instant from, Instant to) {
        return monthly(crossTenant.query(with(tenants, TenantTable.STORES), (tenant, params) -> {
            String s = TenantSchema.validate(tenant.schemaName());
            params.add(ts(from));
            params.add(ts(to));
            return "SELECT to_char(st.created_at AT TIME ZONE '" + ZONE + "', 'YYYY-MM') AS month, COUNT(*) AS n "
                    + "FROM " + s + ".stores st WHERE st.type = 'STORE' AND st.created_at >= ? AND st.created_at < ? "
                    + "GROUP BY 1";
        }, union -> "SELECT u.month, SUM(u.n) AS n FROM (" + union + ") u GROUP BY u.month", MONTH_ROW));
    }

    @Override
    public Map<String, Long> invoicesByMonth(List<TenantBranch> tenants, Instant from, Instant to) {
        return monthly(crossTenant.query(with(tenants, TenantTable.INVOICES), (tenant, params) -> {
            String s = TenantSchema.validate(tenant.schemaName());
            params.add(ts(from));
            params.add(ts(to));
            return "SELECT to_char(i.issue_date, 'YYYY-MM') AS month, COUNT(*) AS n FROM " + s + ".invoices i "
                    + "WHERE " + COUNTED + " AND " + INVOICE_DAY + " >= ? AND " + INVOICE_DAY + " < ? GROUP BY 1";
        }, union -> "SELECT u.month, SUM(u.n) AS n FROM (" + union + ") u GROUP BY u.month", MONTH_ROW));
    }

    @Override
    public Map<String, Long> storeCounts(List<TenantBranch> tenants) {
        Map<String, Long> counts = new HashMap<>();
        crossTenant.query(with(tenants, TenantTable.STORES), (tenant, params) -> {
            String s = TenantSchema.validate(tenant.schemaName());
            return "SELECT " + CrossTenantSql.tenantLiteral(tenant) + "::text AS tenant_id, COUNT(*) AS n FROM " + s
                    + ".stores st WHERE st.type = 'STORE' AND st.is_active = true";
        }, union -> "SELECT u.tenant_id, u.n FROM (" + union + ") u",
                (rs, n) -> Map.entry(rs.getString("tenant_id"), rs.getLong("n")))
                .forEach(e -> counts.put(e.getKey(), e.getValue()));
        return counts;
    }

    @Override
    public Map<String, String> ownerNames(Collection<String> tenantIds) {
        Map<String, String> names = new HashMap<>();
        if (tenantIds.isEmpty()) {
            return names;
        }
        jdbc.query("SELECT DISTINCT ON (m.tenant_id) CAST(m.tenant_id AS text) AS tenant_id, "
                        + "NULLIF(btrim(concat_ws(' ', u.first_name, u.last_name)), '') AS name "
                        + "FROM public.user_tenant_memberships m JOIN public.users u ON u.id = m.user_id "
                        + "WHERE m.role = 'OWNER' AND m.tenant_id = ANY (CAST(string_to_array(?, ',') AS uuid[])) "
                        + "ORDER BY m.tenant_id, (NULLIF(btrim(concat_ws(' ', u.first_name, u.last_name)), '') IS NULL), "
                        + "m.created_at",
                rs -> {
                    String name = rs.getString("name");
                    names.put(rs.getString("tenant_id"), name == null ? AdminLabels.OWNER : name);
                }, String.join(",", tenantIds));
        return names;
    }

    @Override
    public List<ActivityItem> activity(List<TenantBranch> tenants, int limit) {
        List<ActivityItem> events = new ArrayList<>(publicActivity(limit));
        events.addAll(tenantActivity(tenants, limit));
        events.sort(Comparator.comparing(ActivityItem::occurredAt, Comparator.nullsLast(Comparator.reverseOrder())));
        return List.copyOf(events.subList(0, Math.min(limit, events.size())));
    }

    /** New accounts and new users — one statement over the public tables. */
    private List<ActivityItem> publicActivity(int limit) {
        return jdbc.query("(SELECT 'ACCOUNT_CREATED' AS type, CAST(t.id AS text) AS tenant_id, "
                        + "COALESCE(NULLIF(btrim(t.designation), ''), NULLIF(btrim(t.name), '')) AS tenant_name, "
                        + "NULL::text AS detail, t.created_at AS at FROM public.tenants t WHERE " + LIVE_TENANT
                        + " ORDER BY t.created_at DESC LIMIT " + limit + ") UNION ALL "
                        + "(SELECT 'USER_CREATED', NULL, NULL, NULLIF(btrim(concat_ws(' ', u.first_name, u.last_name)), ''), "
                        + "u.created_at FROM public.users u WHERE u.role <> '" + SUPER_ADMIN + "' "
                        + "ORDER BY u.created_at DESC, u.id DESC LIMIT " + limit + ")",
                (rs, n) -> {
                    ActivityType type = ActivityType.valueOf(rs.getString("type"));
                    String tenantName = rs.getString("tenant_name");
                    String detail = rs.getString("detail");
                    if (type == ActivityType.ACCOUNT_CREATED) {
                        tenantName = tenantName == null ? AdminLabels.UNNAMED_TENANT : tenantName;
                        detail = tenantName;
                    } else if (detail == null) {
                        detail = "Utilisateur sans nom";
                    }
                    Timestamp at = rs.getTimestamp("at");
                    return new ActivityItem(type.name(), type.label(), detail, rs.getString("tenant_id"), tenantName,
                            at == null ? null : at.toInstant());
                });
    }

    /**
     * New stores, issued invoices (counted), open quotes and received payments of every tenant holding stores or
     * invoices — one statement per chunk, each leg LIMITed on its index.
     */
    private List<ActivityItem> tenantActivity(List<TenantBranch> tenants, int limit) {
        List<TenantBranch> branches = tenants.stream()
                .filter(t -> t.has(TenantTable.STORES) || t.has(TenantTable.INVOICES)).toList();
        Map<String, String> names = branches.stream()
                .collect(Collectors.toMap(TenantBranch::id, TenantBranch::name, (a, b) -> a));
        List<ActivityItem> events = crossTenant.query(branches, (tenant, params) -> {
            String s = TenantSchema.validate(tenant.schemaName());
            String id = CrossTenantSql.tenantLiteral(tenant) + "::text";
            StringJoiner legs = new StringJoiner(" UNION ALL ");
            if (tenant.has(TenantTable.STORES)) {
                legs.add("(SELECT 'STORE_CREATED'::text AS type, " + id + " AS tenant_id, st.name::text AS detail, "
                        + "NULL::bigint AS amount, st.created_at AS at FROM " + s + ".stores st WHERE st.type = 'STORE' "
                        + "ORDER BY st.created_at DESC LIMIT " + limit + ")");
            }
            if (tenant.has(TenantTable.INVOICES)) {
                legs.add("(SELECT 'INVOICE_CREATED'::text AS type, " + id + " AS tenant_id, NULL::text AS detail, "
                        + "i.total_ttc::bigint AS amount, i.created_at AS at FROM " + s + ".invoices i WHERE " + COUNTED
                        + " ORDER BY i.created_at DESC LIMIT " + limit + ")");
                legs.add("(SELECT 'QUOTE_CREATED'::text AS type, " + id + " AS tenant_id, NULL::text AS detail, "
                        + "i.total_ttc::bigint AS amount, i.created_at AS at FROM " + s + ".invoices i WHERE " + OPEN_QUOTE
                        + " ORDER BY i.created_at DESC LIMIT " + limit + ")");
                if (tenant.has(TenantTable.INVOICE_PAYMENTS)) {
                    legs.add("(SELECT 'PAYMENT_RECEIVED'::text AS type, " + id + " AS tenant_id, NULL::text AS detail, "
                            + "p.amount::bigint AS amount, p.paid_at AS at FROM " + s + ".invoice_payments p "
                            + "JOIN " + s + ".invoices i ON i.id = p.invoice_id WHERE p.paid_at IS NOT NULL "
                            + "AND i.deleted_at IS NULL ORDER BY p.paid_at DESC LIMIT " + limit + ")");
                }
            }
            return "SELECT * FROM (" + legs + ") legs";
        }, union -> "SELECT * FROM (" + union + ") u ORDER BY u.at DESC LIMIT " + limit, (rs, n) -> {
            ActivityType type = ActivityType.valueOf(rs.getString("type"));
            String tenantId = rs.getString("tenant_id");
            Timestamp at = rs.getTimestamp("at");
            return new ActivityItem(type.name(), type.label(), detail(type, rs.getString("detail"),
                    (Long) rs.getObject("amount", Long.class)), tenantId, names.get(tenantId),
                    at == null ? null : at.toInstant());
        });
        events.sort(Comparator.comparing(ActivityItem::occurredAt, Comparator.nullsLast(Comparator.reverseOrder())));
        return events.subList(0, Math.min(limit, events.size()));
    }

    /** « Facture de 12 000 FCFA », « Devis de … », « Paiement de … »; the store name; the label alone without amount. */
    static String detail(ActivityType type, String detail, Long amount) {
        return switch (type) {
            case INVOICE_CREATED -> amount == null ? type.label() : "Facture de " + amount(amount);
            case QUOTE_CREATED -> amount == null ? type.label() : "Devis de " + amount(amount);
            case PAYMENT_RECEIVED -> amount == null ? type.label() : "Paiement de " + amount(amount);
            default -> detail == null ? type.label() : detail;
        };
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static List<TenantBranch> with(List<TenantBranch> tenants, TenantTable table) {
        return tenants.stream().filter(t -> t.has(table)).toList();
    }

    private static final org.springframework.jdbc.core.RowMapper<Map.Entry<String, Long>> MONTH_ROW =
            (rs, n) -> Map.entry(rs.getString("month"), rs.getLong("n"));

    private static Map<String, Long> monthly(List<Map.Entry<String, Long>> rows) {
        Map<String, Long> byMonth = new HashMap<>();
        rows.forEach(e -> byMonth.merge(e.getKey(), e.getValue(), Long::sum));
        return byMonth;
    }

    private static long[] sum(List<long[]> rows, int width) {
        long[] total = new long[width];
        for (long[] row : rows) {
            for (int i = 0; i < width; i++) {
                total[i] += row[i];
            }
        }
        return total;
    }

    /** « 12 500 FCFA » (amounts are whole XAF). */
    static String amount(long value) {
        NumberFormat format = NumberFormat.getIntegerInstance(Locale.FRANCE);
        return format.format(value).replace(' ', ' ').replace(' ', ' ') + " FCFA";
    }

    private static Timestamp ts(Instant instant) {
        return Timestamp.from(instant);
    }
}
