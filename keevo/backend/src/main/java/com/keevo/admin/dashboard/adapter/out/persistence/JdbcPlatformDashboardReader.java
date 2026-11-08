package com.keevo.admin.dashboard.adapter.out.persistence;

import com.keevo.admin.AdminLabels;
import com.keevo.admin.crosstenant.CrossTenantSql;
import com.keevo.admin.crosstenant.TenantBranch;
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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Story 20.13 — platform figures of the super-admin dashboard.
 * <ul>
 *   <li>public tables ({@code users}, {@code tenants}, memberships): one aggregate query per figure;</li>
 *   <li>tenant tables ({@code stores}, {@code invoices}): ONE bounded {@code UNION ALL} statement per figure over the
 *       schemas holding the table (catalogue query of {@link CrossTenantSql}); each branch aggregates its own rows, the
 *       outer query adds them up. Invoices by month use the 20.9 partial expression index
 *       {@code idx_invoices_revenue_day}; recent invoices / quotes use {@code idx_invoices_created}; stores are small
 *       per tenant (plan limits).</li>
 * </ul>
 * Months are {@code yyyy-MM} in Africa/Douala; KV-ADMIN and deleted tenants never count; super-admins never count as
 * users.
 */
@Component
public class JdbcPlatformDashboardReader implements PlatformDashboardReadPort {

    static final String ZONE = "Africa/Douala";
    static final List<String> STORE_COLUMNS = List.of("id", "name", "type", "is_active", "created_at");
    static final List<String> INVOICE_COLUMNS = List.of("id", "kind", "status", "payment_state", "source_sale_id",
            "deleted_at", "superseded_at", "issue_date", "total_ttc", "created_at");

    /** Counted invoice (same predicate as the 20.9 revenue source and its V14 partial index). */
    static final String COUNTED = "i.kind = 'INVOICE' AND i.status IN ('ISSUED', 'PAID') AND i.source_sale_id IS NULL "
            + "AND i.deleted_at IS NULL AND i.superseded_at IS NULL";
    /** The V14 index expression: civil issue date as the WAT midnight instant. */
    static final String INVOICE_DAY = "(i.issue_date::timestamp AT TIME ZONE 'Africa/Lagos')";

    private static final String SUPER_ADMIN = "SUPER_ADMIN";
    private static final String LIVE_TENANT = "t.id <> CAST('" + CrossTenantSql.SYSTEM_TENANT_ID + "' AS uuid) "
            + "AND t.status <> 'DELETED'";

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
        List<TenantBranch> branches = with(tenants, "stores", STORE_COLUMNS);
        if (branches.isEmpty()) {
            return new StoreFigures(0, 0, 0);
        }
        List<Object> params = new ArrayList<>();
        String union = crossTenant.unionAll(branches, tenant -> {
            String s = TenantSchema.validate(tenant.schemaName());
            params.add(ts(monthStart));
            return "SELECT COUNT(*) AS total, COUNT(*) FILTER (WHERE st.is_active) AS active, "
                    + "COUNT(*) FILTER (WHERE st.created_at >= ?) AS created FROM " + s + ".stores st "
                    + "WHERE st.type = 'STORE'";
        });
        return jdbc.queryForObject("SELECT COALESCE(SUM(total), 0) AS total, COALESCE(SUM(active), 0) AS active, "
                        + "COALESCE(SUM(created), 0) AS created FROM (" + union + ") u",
                (rs, n) -> new StoreFigures(rs.getLong("total"), rs.getLong("active"), rs.getLong("created")),
                params.toArray());
    }

    @Override
    public DocumentFigures documentFigures(List<TenantBranch> tenants) {
        List<TenantBranch> branches = with(tenants, "invoices", INVOICE_COLUMNS);
        if (branches.isEmpty()) {
            return new DocumentFigures(0, 0, 0, 0, 0, 0);
        }
        String union = crossTenant.unionAll(branches, tenant -> {
            String s = TenantSchema.validate(tenant.schemaName());
            return "SELECT COUNT(*) FILTER (WHERE " + COUNTED + ") AS invoices, "
                    + "COUNT(*) FILTER (WHERE " + COUNTED + " AND i.payment_state = 'UNPAID') AS unpaid, "
                    + "COUNT(*) FILTER (WHERE " + COUNTED + " AND i.payment_state = 'PARTIAL') AS partial, "
                    + "COUNT(*) FILTER (WHERE " + COUNTED + " AND i.payment_state = 'PAID') AS paid, "
                    + "COUNT(*) FILTER (WHERE " + COUNTED + " AND i.payment_state = 'OVERDUE') AS overdue, "
                    + "COUNT(*) FILTER (WHERE i.kind = 'QUOTE' AND i.deleted_at IS NULL "
                    + "AND i.status NOT IN ('CANCELLED', 'CONVERTED')) AS quotes FROM " + s + ".invoices i";
        });
        return jdbc.queryForObject("SELECT COALESCE(SUM(invoices), 0) AS invoices, COALESCE(SUM(unpaid), 0) AS unpaid, "
                        + "COALESCE(SUM(partial), 0) AS partial, COALESCE(SUM(paid), 0) AS paid, "
                        + "COALESCE(SUM(overdue), 0) AS overdue, COALESCE(SUM(quotes), 0) AS quotes FROM (" + union
                        + ") u",
                (rs, n) -> new DocumentFigures(rs.getLong("invoices"), rs.getLong("unpaid"), rs.getLong("partial"),
                        rs.getLong("paid"), rs.getLong("overdue"), rs.getLong("quotes")));
    }

    @Override
    public Map<String, Long> newUsersByMonth(Instant from, Instant to) {
        return monthly("SELECT to_char(u.created_at AT TIME ZONE '" + ZONE + "', 'YYYY-MM') AS month, COUNT(*) AS n "
                + "FROM public.users u WHERE u.role <> '" + SUPER_ADMIN + "' AND u.created_at >= ? AND u.created_at < ? "
                + "GROUP BY 1", ts(from), ts(to));
    }

    @Override
    public Map<String, Long> newAccountsByMonth(Instant from, Instant to) {
        return monthly("SELECT to_char(t.created_at AT TIME ZONE '" + ZONE + "', 'YYYY-MM') AS month, COUNT(*) AS n "
                + "FROM public.tenants t WHERE " + LIVE_TENANT + " AND t.created_at >= ? AND t.created_at < ? "
                + "GROUP BY 1", ts(from), ts(to));
    }

    @Override
    public Map<String, Long> newStoresByMonth(List<TenantBranch> tenants, Instant from, Instant to) {
        List<TenantBranch> branches = with(tenants, "stores", STORE_COLUMNS);
        if (branches.isEmpty()) {
            return Map.of();
        }
        List<Object> params = new ArrayList<>();
        String union = crossTenant.unionAll(branches, tenant -> {
            String s = TenantSchema.validate(tenant.schemaName());
            params.add(ts(from));
            params.add(ts(to));
            return "SELECT to_char(st.created_at AT TIME ZONE '" + ZONE + "', 'YYYY-MM') AS month, COUNT(*) AS n "
                    + "FROM " + s + ".stores st WHERE st.type = 'STORE' AND st.created_at >= ? AND st.created_at < ? "
                    + "GROUP BY 1";
        });
        return monthly("SELECT u.month, SUM(u.n) AS n FROM (" + union + ") u GROUP BY u.month", params.toArray());
    }

    @Override
    public Map<String, Long> invoicesByMonth(List<TenantBranch> tenants, Instant from, Instant to) {
        List<TenantBranch> branches = with(tenants, "invoices", INVOICE_COLUMNS);
        if (branches.isEmpty()) {
            return Map.of();
        }
        List<Object> params = new ArrayList<>();
        String union = crossTenant.unionAll(branches, tenant -> {
            String s = TenantSchema.validate(tenant.schemaName());
            params.add(ts(from));
            params.add(ts(to));
            return "SELECT to_char(i.issue_date, 'YYYY-MM') AS month, COUNT(*) AS n FROM " + s + ".invoices i "
                    + "WHERE " + COUNTED + " AND " + INVOICE_DAY + " >= ? AND " + INVOICE_DAY + " < ? GROUP BY 1";
        });
        return monthly("SELECT u.month, SUM(u.n) AS n FROM (" + union + ") u GROUP BY u.month", params.toArray());
    }

    @Override
    public Map<String, Long> storeCounts(List<TenantBranch> tenants) {
        List<TenantBranch> branches = with(tenants, "stores", STORE_COLUMNS);
        Map<String, Long> counts = new HashMap<>();
        if (branches.isEmpty()) {
            return counts;
        }
        String union = crossTenant.unionAll(branches, tenant -> {
            String s = TenantSchema.validate(tenant.schemaName());
            return "SELECT " + CrossTenantSql.tenantLiteral(tenant) + "::text AS tenant_id, COUNT(*) AS n FROM " + s
                    + ".stores st WHERE st.type = 'STORE' AND st.is_active = true";
        });
        jdbc.query("SELECT u.tenant_id, u.n FROM (" + union + ") u",
                rs -> {
                    counts.put(rs.getString("tenant_id"), rs.getLong("n"));
                });
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

    /** New stores, invoices and quotes of every tenant — one bounded statement, each leg LIMITed on its index. */
    private List<ActivityItem> tenantActivity(List<TenantBranch> tenants, int limit) {
        List<TenantBranch> branches = with(tenants, "stores", STORE_COLUMNS);
        if (branches.isEmpty()) {
            return List.of();
        }
        Set<String> withInvoices = new HashSet<>();
        with(tenants, "invoices", INVOICE_COLUMNS).forEach(t -> withInvoices.add(t.id()));
        Map<String, String> names = branches.stream()
                .collect(Collectors.toMap(TenantBranch::id, TenantBranch::name, (a, b) -> a));
        String union = crossTenant.unionAll(branches, tenant -> {
            String s = TenantSchema.validate(tenant.schemaName());
            String id = CrossTenantSql.tenantLiteral(tenant) + "::text";
            String stores = "(SELECT 'STORE_CREATED'::text AS type, " + id + " AS tenant_id, st.name::text AS detail, "
                    + "NULL::bigint AS amount, st.created_at AS at FROM " + s + ".stores st WHERE st.type = 'STORE' "
                    + "ORDER BY st.created_at DESC LIMIT " + limit + ")";
            if (!withInvoices.contains(tenant.id())) {
                return "SELECT * FROM " + stores + " x";
            }
            return stores + " UNION ALL (SELECT CASE WHEN i.kind = 'QUOTE' THEN 'QUOTE_CREATED' ELSE 'INVOICE_CREATED' "
                    + "END, " + id + ", NULL::text, i.total_ttc::bigint, i.created_at FROM " + s + ".invoices i "
                    + "WHERE i.deleted_at IS NULL AND i.source_sale_id IS NULL "
                    + "ORDER BY i.created_at DESC LIMIT " + limit + ")";
        });
        return jdbc.query("SELECT * FROM (" + union + ") u ORDER BY u.at DESC LIMIT " + limit,
                (rs, n) -> {
                    ActivityType type = ActivityType.valueOf(rs.getString("type"));
                    String tenantId = rs.getString("tenant_id");
                    String tenantName = names.get(tenantId);
                    String detail = rs.getString("detail");
                    long amount = rs.getLong("amount");
                    if (!rs.wasNull()) {
                        detail = (type == ActivityType.QUOTE_CREATED ? "Devis de " : "Facture de ") + amount(amount);
                    }
                    Timestamp at = rs.getTimestamp("at");
                    return new ActivityItem(type.name(), type.label(), detail, tenantId, tenantName,
                            at == null ? null : at.toInstant());
                });
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /** The given tenants whose schema holds the table with the columns — one catalogue query. */
    private List<TenantBranch> with(List<TenantBranch> tenants, String table, List<String> columns) {
        if (tenants.isEmpty()) {
            return List.of();
        }
        Set<String> present = crossTenant.tenantsWith(null, table, columns).stream()
                .map(TenantBranch::id).collect(Collectors.toSet());
        return tenants.stream().filter(t -> present.contains(t.id())).toList();
    }

    private Map<String, Long> monthly(String sql, Object... params) {
        Map<String, Long> byMonth = new HashMap<>();
        jdbc.query(sql, rs -> {
            byMonth.merge(rs.getString("month"), rs.getLong("n"), Long::sum);
        }, params);
        return byMonth;
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
