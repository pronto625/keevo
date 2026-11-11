package com.keevo.admin.catalog.application.service;

import com.keevo.admin.crosstenant.CrossTenantSql;
import com.keevo.admin.crosstenant.TenantBranch;
import com.keevo.admin.crosstenant.TenantTable;
import com.keevo.identity.api.PlanKind;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.admin.catalog.domain.model.AdminCatalogSummary;
import com.keevo.admin.catalog.domain.model.AdminProductListItem;
import com.keevo.admin.catalog.domain.port.in.GetCatalogSummaryUseCase;
import com.keevo.admin.catalog.domain.port.in.ListProductsQuery;
import com.keevo.admin.catalog.domain.port.in.ListProductsUseCase;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AdminCatalogService — cross-tenant product catalog queries for Super Admin.
 *
 * <p><b>Architecture rules:</b>
 * <ol>
 *   <li>No TenantContext manipulation — uses JdbcTemplate with schema-qualified SQL directly.</li>
 *   <li>schemaName is sourced from {@code public.tenants.schema_name}, validated at provisioning
 *       to match {@code ^kv_[a-z0-9]{6}$}; rows failing the shared
 *       {@link com.keevo.shared.infrastructure.persistence.TenantSchema} gate are skipped before any
 *       SQL interpolation (Story v1s-13-12).</li>
 *   <li>Always excludes system tenant (id = ADMIN_TENANT_ID) from all queries.</li>
 *   <li>Story 19.11b (FUS-NFR11): never one statement per tenant. The tenants come from one {@code public.tenants}
 *       query, their table presence from one catalogue query ({@link CrossTenantSql#withPresence}), the figures and
 *       rows from chunked {@code UNION ALL} statements ({@link CrossTenantSql#query}, 100 tenants each). A page reads
 *       at most {@code offset + size} rows per tenant and per chunk (top-N sort on {@code updated_at}, no index on it
 *       yet — 19-11c), merged here;
 *       the total is one {@code COUNT} union. A chunk failing on one tenant's schema is retried tenant by tenant and
 *       only that tenant is skipped; connection / resource failures fail the request.</li>
 *   <li>Story 19.11c: the branch order is served by the catalog V4 index {@code (updated_at DESC NULLS LAST, id)};
 *       the name search is {@code lower(name) LIKE} (catalog V3 trigram index, the typed {@code %} / {@code _} are
 *       literal); the CSV export ({@link #prepareExport}) streams the rows chunk by chunk (one chunk of 100 tenants
 *       in memory), grouped by company in name order, most recently updated first inside a company.</li>
 * </ol>
 */
@Service
public class AdminCatalogService implements ListProductsUseCase, GetCatalogSummaryUseCase {

    private static final String ADMIN_TENANT_ID = "00000000-0000-0000-0000-000000000000";

    private final JdbcTemplate jdbc;
    private final CrossTenantSql crossTenant;

    public AdminCatalogService(JdbcTemplate jdbc, CrossTenantSql crossTenant) {
        this.jdbc = jdbc;
        this.crossTenant = crossTenant;
    }

    // ── ListProductsUseCase ───────────────────────────────────────────────────

    @Override
    // Deliberately NOT @Transactional: each per-tenant statement autocommits, so one broken tenant
    // schema cannot abort (25P02) the PostgreSQL transaction of the following tenants' queries.
    public Page<AdminProductListItem> execute(ListProductsQuery query) {
        boolean export = query.pageSize() == Integer.MAX_VALUE;
        boolean all = export || query.pageSize() == 0; // page size 0 keeps its historical meaning: every row
        if (!export && (query.page() < 0 || query.pageSize() < 0)) {
            throw DomainException.userFacing(ErrorCode.VALIDATION_ERROR, "Le numéro et la taille de page doivent être positifs");
        }
        List<TenantSchema> tenants = loadMatchingTenantSchemas(query);
        Map<String, TenantSchema> byId = new HashMap<>();
        tenants.forEach(t -> byId.put(t.id(), t));
        List<TenantBranch> branches = withPresence(tenants, TenantTable.PRODUCTS_LIST);

        long total = 0;
        for (Long chunk : crossTenant.query(branches, (tenant, params) -> "SELECT COUNT(*) AS n FROM "
                        + productsFrom(tenant) + " WHERE " + productFilters(query, params),
                union -> "SELECT COALESCE(SUM(n), 0) FROM (" + union + ") u", (rs, n) -> rs.getLong(1))) {
            total += chunk;
        }

        int pageSize = export ? (int) Math.min(total, Integer.MAX_VALUE) : query.pageSize();
        long offset = all ? 0 : (long) query.page() * query.pageSize();
        List<AdminProductListItem> slice = List.of();
        if (total > offset) {
            // Each branch and each chunk returns at most offset + size rows, in the global order.
            String limit = all ? "" : " LIMIT " + (offset + pageSize);
            // Inside a branch the tenant is constant: the product order is enough (on the input columns).
            String branchOrder = all ? "" : " ORDER BY " + BRANCH_ORDER;
            String outerOrder = all ? "" : " ORDER BY " + ORDER;
            List<AdminProductListItem> rows = new ArrayList<>(crossTenant.query(branches,
                    (tenant, params) -> productRow(tenant, query, params, "") + branchOrder + limit,
                    union -> "SELECT * FROM (" + union + ") u" + outerOrder + limit,
                    (rs, n) -> productItem(rs, byId)));
            rows.removeIf(java.util.Objects::isNull);
            // Global sort: most recently updated first (chunks merged), then the same tie-break as the SQL.
            rows.sort(Comparator.comparing(AdminProductListItem::updatedAt,
                            Comparator.nullsLast(Comparator.<java.time.Instant>reverseOrder()))
                    .thenComparing(AdminProductListItem::tenantId)
                    .thenComparing(AdminProductListItem::id));
            slice = rows.stream().skip(offset).limit(all ? rows.size() : pageSize).toList();
        }

        return new PageImpl<>(slice, PageRequest.of(
                export ? 0 : query.page(),
                pageSize == 0 ? 25 : pageSize),
                total);
    }

    /**
     * Story 19.11c — the CSV export of the products matching the filters, prepared then streamed: preparing loads the
     * matching tenants and their table presence (2 statements; filter errors surface here, before any byte is
     * written), {@link ProductExport#forEach} then reads one union per 100 tenants and hands each row to the sink —
     * never every product of every tenant in memory. Rows are grouped by company (company name order, as the tenant
     * list), most recently updated first inside a company.
     */
    public ProductExport prepareExport(ListProductsQuery query) {
        List<TenantSchema> tenants = loadMatchingTenantSchemas(query);
        Map<String, TenantSchema> byId = new HashMap<>();
        Map<String, Integer> position = new HashMap<>();
        tenants.forEach(t -> {
            byId.put(t.id(), t);
            position.putIfAbsent(t.id(), position.size());
        });
        List<TenantBranch> branches = withPresence(tenants, TenantTable.PRODUCTS_LIST);
        // EXPORT_CHUNK tenants per statement: the rows of one statement are held at once (driver buffer + list).
        return sink -> crossTenant.forEachChunk(branches, EXPORT_CHUNK,
                (tenant, params) -> productRow(tenant, query, params, position.get(tenant.id()) + " AS pos, "),
                union -> "SELECT * FROM (" + union + ") u ORDER BY pos, " + ORDER,
                (rs, n) -> productItem(rs, byId),
                rows -> rows.stream().filter(java.util.Objects::nonNull).forEach(sink));
    }

    /** Tenants per statement of the export (memory bound: one statement's rows). */
    static final int EXPORT_CHUNK = 10;

    /** A prepared export: streams its rows to a sink. */
    @FunctionalInterface
    public interface ProductExport {
        void forEach(java.util.function.Consumer<AdminProductListItem> sink);
    }

    /** One tenant's product rows ({@code extra} columns first), filters bound to {@code params}; no order / limit. */
    private static String productRow(TenantBranch tenant, ListProductsQuery query, List<Object> params, String extra) {
        boolean categories = tenant.has(TenantTable.PRODUCTS_CATEGORY, TenantTable.CATEGORIES);
        return "SELECT " + extra + CrossTenantSql.tenantLiteral(tenant) + "::text AS tenant_id, p.id::text AS id, "
                + "p.name, p.price, p.stock_quantity, p.status, p.updated_at, "
                + (categories ? "COALESCE(c.name, '')" : "''") + " AS category_name "
                + "FROM " + productsFrom(tenant)
                + (categories ? " LEFT JOIN " + validSchema(tenant) + ".categories c ON c.id = p.category_id" : "")
                + " WHERE " + productFilters(query, params);
    }

    /** The list item of a product row; null when the row's tenant is not one of {@code byId} (never shown). */
    private static AdminProductListItem productItem(java.sql.ResultSet rs, Map<String, TenantSchema> byId)
            throws java.sql.SQLException {
        TenantSchema tenant = byId.get(rs.getString("tenant_id"));
        if (tenant == null) return null;
        Timestamp ts = rs.getTimestamp("updated_at");
        return new AdminProductListItem(rs.getString("id"), rs.getString("name"), tenant.id(),
                tenant.name(), tenant.plan(), tenant.schemaName(), rs.getString("category_name"),
                rs.getLong("price"), rs.getInt("stock_quantity"), rs.getString("status"),
                ts != null ? ts.toInstant() : null);
    }

    /** Global order of the product list: most recently updated first, ties by tenant then product id. */
    private static final String ORDER = "updated_at DESC NULLS LAST, tenant_id COLLATE \"C\", id COLLATE \"C\"";
    /** The same order inside one tenant's branch, on the product columns. */
    // A uuid compares byte by byte, i.e. as its lowercase hex text in the C collation: same order as the outer id.
    private static final String BRANCH_ORDER = "p.updated_at DESC NULLS LAST, p.id";

    // ── GetCatalogSummaryUseCase ──────────────────────────────────────────────

    @Override
    // Deliberately NOT @Transactional: each per-tenant statement autocommits, so one broken tenant
    // schema cannot abort (25P02) the PostgreSQL transaction of the following tenants' queries.
    public AdminCatalogSummary execute() {
        // 19.11b: the six counts of every tenant in one branch each, summed per chunk.
        long[] sums = new long[6];
        for (long[] chunk : crossTenant.query(withPresence(loadAllActiveSchemas(), TenantTable.PRODUCTS_TABLE),
                (tenant, params) -> {
                    // A count whose columns are absent from the schema (older version) reads 0, the others are kept.
                    boolean status = tenant.has(TenantTable.PRODUCTS_STATUS);
                    boolean stock = tenant.has(TenantTable.PRODUCTS_STOCK);
                    return "SELECT COUNT(*) AS total, "
                            + count(status, "status = 'ACTIVE' AND archived = false") + " AS active, " // 17.10: same as the plan count
                            + count(status, "status = 'DRAFT'") + " AS draft, "
                            + count(stock, "stock_quantity > 0 AND stock_quantity <= 5 AND track_stock = true") + " AS low_stock, " // 17.12
                            + count(stock, "stock_quantity = 0 AND track_stock = true") + " AS out_stock, " // 17.12: untracked never « out of stock »
                            + count(tenant.has(TenantTable.PRODUCTS_CREATED), "created_at >= NOW() - INTERVAL '7 days'")
                            + " AS this_week FROM " + validSchema(tenant) + ".products";
                },
                union -> "SELECT COALESCE(SUM(total), 0), COALESCE(SUM(active), 0), COALESCE(SUM(draft), 0), "
                        + "COALESCE(SUM(low_stock), 0), COALESCE(SUM(out_stock), 0), COALESCE(SUM(this_week), 0) "
                        + "FROM (" + union + ") u",
                (rs, n) -> {
                    long[] row = new long[6];
                    for (int i = 0; i < row.length; i++) row[i] = rs.getLong(i + 1);
                    return row;
                })) {
            for (int i = 0; i < sums.length; i++) sums[i] += chunk[i];
        }
        return new AdminCatalogSummary(sums[0], sums[1], sums[2], sums[3], sums[4], sums[5]);
    }

    /** The tenants whose schema holds {@code required} (presence in one catalogue query). */
    private List<TenantBranch> withPresence(List<TenantSchema> tenants, TenantTable required) {
        return crossTenant.withPresence(tenants.stream()
                        .map(t -> new TenantBranch(t.id(), t.name(), t.schemaName(), "ACTIVE", null))
                        .toList())
                .stream().filter(t -> t.has(required)).toList();
    }

    /** {@code CAST(COUNT(*) FILTER (WHERE predicate) AS bigint)}, or 0 when its columns are absent. */
    private static String count(boolean present, String predicate) {
        return present ? "COUNT(*) FILTER (WHERE " + predicate + ")" : "CAST(0 AS bigint)";
    }

    /** The branch schema, validated at use before interpolation. */
    private static String validSchema(TenantBranch tenant) {
        return com.keevo.shared.infrastructure.persistence.TenantSchema.validate(tenant.schemaName());
    }

    /** {@code <schema>.products p} — the schema validated before interpolation. */
    private static String productsFrom(TenantBranch tenant) {
        return validSchema(tenant) + ".products p";
    }

    /** The list filters (search, status, stock level) on {@code p}; bound values appended to {@code params}. */
    private static String productFilters(ListProductsQuery q, List<Object> params) {
        StringBuilder sql = new StringBuilder("TRUE");
        if (q.search() != null && !q.search().isBlank()) {
            // 19.11c: same case-insensitive « contains » as ILIKE, in the form of the catalog V3 trigram index
            // (lower(name)); the typed % and _ are literal characters.
            sql.append(" AND lower(p.name) LIKE lower(?) ESCAPE '\\'");
            params.add("%" + escapeLike(q.search().trim()) + "%");
        }
        if (q.status() != null && !q.status().isBlank() && !"ALL".equals(q.status())) {
            sql.append(" AND p.status = ?");
            params.add(q.status());
        }
        if ("OUT".equals(q.stockLevel())) {
            sql.append(" AND p.stock_quantity = 0");
        } else if ("LOW".equals(q.stockLevel())) {
            sql.append(" AND p.stock_quantity > 0 AND p.stock_quantity <= 5");
        } else if ("OK".equals(q.stockLevel())) {
            sql.append(" AND p.stock_quantity > 5");
        }
        return sql.toString();
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /** Escapes the LIKE wildcards ({@code \\} first, then {@code %} and {@code _}) for {@code ESCAPE '\\'}. */
    static String escapeLike(String term) {
        return term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    /** Plan class of the product list ({@code tenantPlan} and its filter): the free kind, else paying. */
    static final String PLAN_FREE = PlanKind.FREE.name();
    static final String PLAN_PAID = "PAID";

    /**
     * Story 19.7a: the class of a plan from its kind (catalogue) — PAID for a trial or paid plan, FREE otherwise
     * (including a code the catalogue does not know, {@code null}: shown as before, never dropped).
     */
    static String planClass(String kind) {
        return kind == null || PLAN_FREE.equals(kind) ? PLAN_FREE : PLAN_PAID;
    }

    private List<TenantSchema> loadMatchingTenantSchemas(ListProductsQuery q) {
        StringBuilder sql = new StringBuilder(
                "SELECT t.id::text, t.name, t.schema_name, pl.kind "
                + "FROM public.tenants t LEFT JOIN public.plans pl ON pl.code = t.plan_type "
                + "WHERE t.id <> '").append(ADMIN_TENANT_ID).append("'::uuid "
                + "AND t.status = 'ACTIVE'");

        List<Object> params = new ArrayList<>();

        if (q.tenantId() != null && !q.tenantId().isBlank()) {
            sql.append(" AND t.id = ?::uuid");
            // 19.11c: a malformed id is 422 « Compte invalide » (was a SQL error, 500) — before any export byte
            params.add(CrossTenantSql.requireUuid(q.tenantId(), CrossTenantSql.INVALID_TENANT));
        }

        // Story 19.7a: FREE / PAID are plan kinds (PAID = kinds TRIAL and PAID), joined on the catalogue
        if (PLAN_PAID.equals(q.plan())) {
            sql.append(" AND pl.kind IN (?, ?)");
            params.add(PlanKind.TRIAL.name());
            params.add(PlanKind.PAID.name());
        } else if (PLAN_FREE.equals(q.plan())) {
            sql.append(" AND (pl.kind = ? OR pl.kind IS NULL)"); // a code the catalogue does not know: free
            params.add(PlanKind.FREE.name());
        }

        sql.append(" ORDER BY t.name");

        return onlyValidSchemas(jdbc.query(sql.toString(), (rs, rowNum) -> {
            String plan = planClass(rs.getString("kind"));
            return new TenantSchema(
                    rs.getString("id"),
                    rs.getString("name"),
                    rs.getString("schema_name"),
                    plan);
        }, params.toArray()));
    }

    private List<TenantSchema> loadAllActiveSchemas() {
        return onlyValidSchemas(jdbc.query(
                "SELECT t.id::text, t.name, t.schema_name, pl.kind "
                + "FROM public.tenants t LEFT JOIN public.plans pl ON pl.code = t.plan_type "
                + "WHERE t.id <> '" + ADMIN_TENANT_ID + "'::uuid "
                + "AND t.status = 'ACTIVE' "
                + "ORDER BY t.name",
                (rs, rowNum) -> {
                    String plan = planClass(rs.getString("kind"));
                    return new TenantSchema(
                            rs.getString("id"),
                            rs.getString("name"),
                            rs.getString("schema_name"),
                            plan);
                }));
    }

    /** Drops rows whose schema name fails the shared tenant-schema gate (never interpolated). */
    private static List<TenantSchema> onlyValidSchemas(List<TenantSchema> tenants) {
        return tenants.stream()
                .filter(t -> {
                    boolean valid = com.keevo.shared.infrastructure.persistence.TenantSchema.isValid(t.schemaName());
                    if (!valid) {
                        // Visible, not silent (follow-up review v1s-13-12); raw value never echoed.
                        org.slf4j.LoggerFactory.getLogger(AdminCatalogService.class).warn(
                                "Tenant {} skipped from the admin catalog: invalid schema_name", t.id());
                    }
                    return valid;
                })
                .toList();
    }

    // ── Inner record ──────────────────────────────────────────────────────────

    private record TenantSchema(String id, String name, String schemaName, String plan) {}
}
