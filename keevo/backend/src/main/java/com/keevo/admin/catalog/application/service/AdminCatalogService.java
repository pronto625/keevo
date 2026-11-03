package com.keevo.admin.catalog.application.service;

import com.keevo.identity.api.PlanKind;
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
import java.util.List;

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
 *   <li>Phase 1 pagination: collect all matching rows in memory, then slice. Acceptable for
 *       &lt;200 tenants × avg 250 products = &lt;50k rows.</li>
 * </ol>
 */
@Service
public class AdminCatalogService implements ListProductsUseCase, GetCatalogSummaryUseCase {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(AdminCatalogService.class);

    private static final String ADMIN_TENANT_ID = "00000000-0000-0000-0000-000000000000";

    private final JdbcTemplate jdbc;

    public AdminCatalogService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ── ListProductsUseCase ───────────────────────────────────────────────────

    @Override
    // Deliberately NOT @Transactional: each per-tenant statement autocommits, so one broken tenant
    // schema cannot abort (25P02) the PostgreSQL transaction of the following tenants' queries.
    public Page<AdminProductListItem> execute(ListProductsQuery query) {
        List<TenantSchema> tenants = loadMatchingTenantSchemas(query);

        List<AdminProductListItem> all = new ArrayList<>();
        for (TenantSchema tenant : tenants) {
            List<AdminProductListItem> products = safeQueryProducts(tenant, query);
            all.addAll(products);
        }

        // Global sort: most recently updated first
        all.sort(Comparator.comparing(
                AdminProductListItem::updatedAt,
                Comparator.nullsLast(Comparator.reverseOrder())));

        long total = all.size();
        int pageSize = query.pageSize() == Integer.MAX_VALUE ? (int) total : query.pageSize();
        int offset = query.pageSize() == Integer.MAX_VALUE ? 0 : query.page() * query.pageSize();

        List<AdminProductListItem> slice = all.stream()
                .skip(offset)
                .limit(pageSize == 0 ? total : pageSize)
                .toList();

        return new PageImpl<>(slice, PageRequest.of(
                query.pageSize() == Integer.MAX_VALUE ? 0 : query.page(),
                pageSize == 0 ? 25 : pageSize),
                total);
    }

    // ── GetCatalogSummaryUseCase ──────────────────────────────────────────────

    @Override
    // Deliberately NOT @Transactional: each per-tenant statement autocommits, so one broken tenant
    // schema cannot abort (25P02) the PostgreSQL transaction of the following tenants' queries.
    public AdminCatalogSummary execute() {
        List<TenantSchema> tenants = loadAllActiveSchemas();

        long total = 0, active = 0, draft = 0, lowStock = 0, outStock = 0, thisWeek = 0;

        for (TenantSchema t : tenants) {
            String s = t.schemaName();
            if (!com.keevo.shared.infrastructure.persistence.TenantSchema.isValid(s)) { // onlyValidSchemas() filters; re-checked at use
                LOG.warn("Tenant {} skipped from the admin catalog: invalid schema_name", t.id());
                continue;
            }
            total    += safeCount("SELECT COUNT(*) FROM " + s + ".products");
            active   += safeCount("SELECT COUNT(*) FROM " + s + ".products WHERE status = 'ACTIVE' AND archived = false"); // 17.10: same as the plan count
            draft    += safeCount("SELECT COUNT(*) FROM " + s + ".products WHERE status = 'DRAFT'");
            lowStock += safeCount("SELECT COUNT(*) FROM " + s + ".products WHERE stock_quantity > 0 AND stock_quantity <= 5 AND track_stock = true"); // 17.12
            outStock += safeCount("SELECT COUNT(*) FROM " + s + ".products WHERE stock_quantity = 0 AND track_stock = true"); // 17.12: untracked never « out of stock »
            thisWeek += safeCount("SELECT COUNT(*) FROM " + s + ".products WHERE created_at >= NOW() - INTERVAL '7 days'");
        }

        return new AdminCatalogSummary(total, active, draft, lowStock, outStock, thisWeek);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

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
            params.add(q.tenantId());
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

    private List<AdminProductListItem> safeQueryProducts(TenantSchema tenant, ListProductsQuery q) {
        if (!com.keevo.shared.infrastructure.persistence.TenantSchema.isValid(tenant.schemaName())) { // onlyValidSchemas() filters; re-checked at use
            LOG.warn("Tenant {} skipped from the admin catalog: invalid schema_name", tenant.id());
            return List.of();
        }
        try {
            StringBuilder sql = new StringBuilder(
                    "SELECT p.id::text, p.name, p.price, p.stock_quantity, p.status, p.updated_at, "
                    + "COALESCE(c.name, '') AS category_name "
                    + "FROM " + tenant.schemaName() + ".products p "
                    + "LEFT JOIN " + tenant.schemaName() + ".categories c ON c.id = p.category_id "
                    + "WHERE TRUE");

            List<Object> params = new ArrayList<>();

            if (q.search() != null && !q.search().isBlank()) {
                sql.append(" AND p.name ILIKE ?");
                params.add("%" + q.search().trim() + "%");
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

            sql.append(" ORDER BY p.updated_at DESC");

            final String tenantId   = tenant.id();
            final String tenantName = tenant.name();
            final String tenantPlan = tenant.plan();
            final String schema     = tenant.schemaName();

            return jdbc.query(sql.toString(), (rs, rowNum) -> {
                Timestamp ts = rs.getTimestamp("updated_at");
                return new AdminProductListItem(
                        rs.getString("id"),
                        rs.getString("name"),
                        tenantId,
                        tenantName,
                        tenantPlan,
                        schema,
                        rs.getString("category_name"),
                        rs.getLong("price"),
                        rs.getInt("stock_quantity"),
                        rs.getString("status"),
                        ts != null ? ts.toInstant() : null
                );
            }, params.toArray());

        } catch (Exception e) {
            return List.of();
        }
    }

    private long safeCount(String sql) {
        try {
            Long count = jdbc.queryForObject(sql, Long.class);
            return count != null ? count : 0L;
        } catch (Exception e) {
            return 0L;
        }
    }

    // ── Inner record ──────────────────────────────────────────────────────────

    private record TenantSchema(String id, String name, String schemaName, String plan) {}
}
