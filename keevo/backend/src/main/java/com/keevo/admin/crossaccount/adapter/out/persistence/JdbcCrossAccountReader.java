package com.keevo.admin.crossaccount.adapter.out.persistence;

import com.keevo.admin.crossaccount.domain.model.CrossAccountViews;
import com.keevo.admin.crossaccount.domain.model.CrossAccountViews.CustomerDetail;
import com.keevo.admin.crossaccount.domain.model.CrossAccountViews.CustomerItem;
import com.keevo.admin.crossaccount.domain.model.CrossAccountViews.ExpenseDetail;
import com.keevo.admin.crossaccount.domain.model.CrossAccountViews.ExpenseItem;
import com.keevo.admin.crossaccount.domain.model.CrossAccountViews.ExpensePage;
import com.keevo.admin.crossaccount.domain.model.CrossAccountViews.ProductDetail;
import com.keevo.admin.crossaccount.domain.model.CrossAccountViews.ProductItem;
import com.keevo.admin.crossaccount.domain.port.out.CrossAccountReadPort;
import com.keevo.admin.crosstenant.AdminParams;
import com.keevo.admin.crosstenant.CrossTenantLists;
import com.keevo.admin.crosstenant.CrossTenantSql;
import com.keevo.admin.crosstenant.CursorPage;
import com.keevo.admin.crosstenant.KeysetSort;
import com.keevo.admin.crosstenant.TenantBranch;
import com.keevo.expenses.api.ExpenseLabels;
import com.keevo.shared.infrastructure.persistence.TenantSchema;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Story 20.13 — cross-tenant reads of the super-admin customer / product / expense lists.
 *
 * <p>Lists: one catalogue query (the tenant schemas holding the table with the columns read) and one bounded
 * {@code UNION ALL} statement ({@link CrossTenantLists}). Sort keys follow existing indexes of each table:
 * customers / products on the sync keyset index {@code (updated_at, CAST(id AS text))}, expenses on
 * {@code idx_expenses_date (expense_date, created_at, id) WHERE archived_at IS NULL}. Details: one schema-qualified
 * query on the primary key. Every schema is validated by {@link TenantSchema} before interpolation.
 */
@Component
public class JdbcCrossAccountReader implements CrossAccountReadPort {

    static final KeysetSort CUSTOMER_SORT = new KeysetSort(List.of(
            new KeysetSort.Key("c.updated_at", "timestamptz"),
            new KeysetSort.Key("CAST(c.id AS text)", "text")), true);
    static final KeysetSort PRODUCT_SORT = new KeysetSort(List.of(
            new KeysetSort.Key("p.updated_at", "timestamptz"),
            new KeysetSort.Key("CAST(p.id AS text)", "text")), true);
    static final KeysetSort EXPENSE_SORT = new KeysetSort(List.of(
            new KeysetSort.Key("e.expense_date", "date"),
            new KeysetSort.Key("e.created_at", "timestamptz"),
            new KeysetSort.Key("e.id", "uuid")), true);

    static final List<String> CLIENT_COLUMNS = List.of("id", "name", "phone", "email", "store_id", "archived",
            "created_at", "updated_at", "marketing_consent_at", "marketing_opt_out_at");
    static final List<String> PRODUCT_COLUMNS = List.of("id", "name", "price", "stock_quantity", "track_stock",
            "status", "archived", "category_id", "barcode", "merged_into_id", "updated_at");
    static final List<String> EXPENSE_COLUMNS = List.of("id", "store_id", "category", "amount", "expense_date",
            "label", "note", "payment_method", "created_at", "archived_at");

    private final JdbcTemplate jdbc;
    private final CrossTenantSql crossTenant;
    private final CrossTenantLists lists;

    public JdbcCrossAccountReader(JdbcTemplate jdbc, CrossTenantSql crossTenant, CrossTenantLists lists) {
        this.jdbc = jdbc;
        this.crossTenant = crossTenant;
        this.lists = lists;
    }

    // ── Customers ─────────────────────────────────────────────────────────────

    @Override
    public CursorPage<CustomerItem> customers(CustomerCriteria c) {
        List<TenantBranch> tenants = crossTenant.tenantsWith(c.tenantId(), "clients", CLIENT_COLUMNS);
        Map<String, String> names = names(tenants);
        return lists.page(tenants, CUSTOMER_SORT, c.cursor(), c.filters(), c.limit(), (tenant, params) -> {
            String s = TenantSchema.validate(tenant.schemaName());
            StringBuilder where = new StringBuilder("c.archived = false");
            if (c.storeId() != null) {
                where.append(" AND c.store_id = CAST(? AS uuid)");
                params.add(c.storeId());
            }
            if (c.search() != null) {
                where.append(" AND (lower(c.name) LIKE ? OR c.phone LIKE ?)");
                params.add(AdminParams.likePattern(c.search()));
                params.add(AdminParams.likePattern(c.search()));
            }
            return new CrossTenantLists.Branch(
                    "CAST(c.id AS text) AS id, c.name, c.phone, c.email, CAST(c.store_id AS text) AS store_id, "
                    + "st.name AS store_name, (c.marketing_consent_at IS NOT NULL AND c.marketing_opt_out_at IS NULL) "
                    + "AS consent, c.created_at, c.updated_at",
                    s + ".clients c LEFT JOIN " + s + ".stores st ON st.id = c.store_id", where.toString());
        }, (rs, n) -> new CustomerItem(rs.getString("tenant_id"), names.get(rs.getString("tenant_id")),
                rs.getString("id"), rs.getString("name"), rs.getString("phone"), rs.getString("email"),
                rs.getString("store_id"), rs.getString("store_name"), rs.getBoolean("consent"),
                instant(rs, "created_at"), instant(rs, "updated_at")));
    }

    @Override
    public Optional<CustomerDetail> customer(TenantBranch tenant, String customerId) {
        if (crossTenant.tenantsWith(tenant.id(), "clients", CLIENT_COLUMNS).isEmpty()) {
            return Optional.empty();
        }
        String s = TenantSchema.validate(tenant.schemaName());
        return jdbc.query("SELECT CAST(c.id AS text) AS id, c.name, c.phone, c.email, CAST(c.store_id AS text) AS store_id, "
                        + "st.name AS store_name, c.client_type, c.niu, c.rccm, c.address, c.contact_name, c.website, "
                        + "c.birth_date, c.notes, c.archived, c.marketing_consent_at, c.marketing_opt_out_at, "
                        + "c.created_at, c.updated_at FROM " + s + ".clients c LEFT JOIN " + s + ".stores st "
                        + "ON st.id = c.store_id WHERE c.id = CAST(? AS uuid)",
                (rs, n) -> new CustomerDetail(tenant.id(), tenant.name(), rs.getString("id"), rs.getString("name"),
                        rs.getString("phone"), rs.getString("email"), rs.getString("store_id"),
                        rs.getString("store_name"), rs.getString("client_type"),
                        CrossAccountViews.clientTypeLabel(rs.getString("client_type")), rs.getString("niu"),
                        rs.getString("rccm"), rs.getString("address"), rs.getString("contact_name"),
                        rs.getString("website"), date(rs, "birth_date"), rs.getString("notes"),
                        rs.getBoolean("archived"),
                        rs.getTimestamp("marketing_consent_at") != null && rs.getTimestamp("marketing_opt_out_at") == null,
                        instant(rs, "marketing_consent_at"), instant(rs, "marketing_opt_out_at"),
                        instant(rs, "created_at"), instant(rs, "updated_at")),
                customerId).stream().findFirst();
    }

    // ── Products ──────────────────────────────────────────────────────────────

    @Override
    public CursorPage<ProductItem> products(ProductCriteria c) {
        List<TenantBranch> tenants = crossTenant.tenantsWith(c.tenantId(), "products", PRODUCT_COLUMNS);
        Map<String, String> names = names(tenants);
        return lists.page(tenants, PRODUCT_SORT, c.cursor(), c.filters(), c.limit(), (tenant, params) -> {
            String s = TenantSchema.validate(tenant.schemaName());
            StringBuilder where = new StringBuilder(c.archived()
                    ? "p.archived = true" : "p.archived = false AND p.merged_into_id IS NULL");
            if (c.search() != null) {
                where.append(" AND (lower(p.name) LIKE ? OR p.barcode = ?)");
                params.add(AdminParams.likePattern(c.search()));
                params.add(c.search());
            }
            return new CrossTenantLists.Branch(
                    "CAST(p.id AS text) AS id, p.name, p.price, p.stock_quantity, p.track_stock, p.status, "
                    + "p.archived, cat.name AS category_name, p.barcode, p.updated_at",
                    s + ".products p LEFT JOIN " + s + ".categories cat ON cat.id = p.category_id",
                    where.toString());
        }, (rs, n) -> new ProductItem(rs.getString("tenant_id"), names.get(rs.getString("tenant_id")),
                rs.getString("id"), rs.getString("name"), rs.getLong("price"), rs.getInt("stock_quantity"),
                rs.getBoolean("track_stock"), rs.getString("status"),
                CrossAccountViews.productStatusLabel(rs.getString("status"), rs.getBoolean("archived")),
                rs.getString("category_name"), rs.getString("barcode"), instant(rs, "updated_at")));
    }

    @Override
    public Optional<ProductDetail> product(TenantBranch tenant, String productId) {
        if (crossTenant.tenantsWith(tenant.id(), "products", PRODUCT_COLUMNS).isEmpty()) {
            return Optional.empty();
        }
        String s = TenantSchema.validate(tenant.schemaName());
        return jdbc.query("SELECT CAST(p.id AS text) AS id, p.name, p.description, p.price, p.buy_price, "
                        + "p.stock_quantity, p.minimum_threshold, p.track_stock, p.status, p.archived, "
                        + "cat.name AS category_name, p.barcode, p.created_at, p.updated_at FROM " + s + ".products p "
                        + "LEFT JOIN " + s + ".categories cat ON cat.id = p.category_id WHERE p.id = CAST(? AS uuid)",
                (rs, n) -> new ProductDetail(tenant.id(), tenant.name(), rs.getString("id"), rs.getString("name"),
                        rs.getString("description"), rs.getLong("price"), rs.getLong("buy_price"),
                        rs.getInt("stock_quantity"), rs.getInt("minimum_threshold"), rs.getBoolean("track_stock"),
                        rs.getString("status"),
                        CrossAccountViews.productStatusLabel(rs.getString("status"), rs.getBoolean("archived")),
                        rs.getString("category_name"), rs.getString("barcode"), instant(rs, "created_at"),
                        instant(rs, "updated_at")),
                productId).stream().findFirst();
    }

    // ── Expenses ──────────────────────────────────────────────────────────────

    @Override
    public ExpensePage expenses(ExpenseCriteria c) {
        List<TenantBranch> tenants = crossTenant.tenantsWith(c.tenantId(), "expenses", EXPENSE_COLUMNS);
        Map<String, String> names = names(tenants);
        Function<List<Object>, String> filter = params -> {
            StringBuilder where = new StringBuilder("e.archived_at IS NULL");
            if (c.storeId() != null) {
                where.append(" AND e.store_id = CAST(? AS uuid)");
                params.add(c.storeId());
            }
            if (c.category() != null) {
                where.append(" AND e.category = ?");
                params.add(c.category());
            }
            if (c.from() != null) {
                where.append(" AND e.expense_date >= ?");
                params.add(Date.valueOf(c.from()));
            }
            if (c.to() != null) {
                where.append(" AND e.expense_date <= ?");
                params.add(Date.valueOf(c.to()));
            }
            if (c.search() != null) {
                where.append(" AND (lower(e.label) LIKE ? OR lower(e.note) LIKE ?)");
                params.add(AdminParams.likePattern(c.search()));
                params.add(AdminParams.likePattern(c.search()));
            }
            return where.toString();
        };
        CursorPage<ExpenseItem> page = lists.page(tenants, EXPENSE_SORT, c.cursor(), c.filters(), c.limit(),
                (tenant, params) -> {
                    String s = TenantSchema.validate(tenant.schemaName());
                    return new CrossTenantLists.Branch(
                            "CAST(e.id AS text) AS id, CAST(e.store_id AS text) AS store_id, st.name AS store_name, "
                            + "e.category, e.amount, e.expense_date, e.label, e.payment_method, e.created_at",
                            s + ".expenses e LEFT JOIN " + s + ".stores st ON st.id = e.store_id",
                            filter.apply(params));
                }, (rs, n) -> new ExpenseItem(rs.getString("tenant_id"), names.get(rs.getString("tenant_id")),
                        rs.getString("id"), rs.getString("store_id"), rs.getString("store_name"),
                        rs.getString("category"), ExpenseLabels.categoryLabel(rs.getString("category")).orElse(null),
                        rs.getBigDecimal("amount"), date(rs, "expense_date"), rs.getString("label"),
                        rs.getString("payment_method"),
                        ExpenseLabels.paymentMethodLabel(rs.getString("payment_method")).orElse(null),
                        instant(rs, "created_at")));
        return new ExpensePage(page, totalAmount(tenants, filter));
    }

    /** Σ amount of every expense matching the filters, all tenants — one statement. */
    private BigDecimal totalAmount(List<TenantBranch> tenants, Function<List<Object>, String> filter) {
        if (tenants.isEmpty()) {
            return BigDecimal.ZERO;
        }
        List<Object> params = new ArrayList<>();
        String union = crossTenant.unionAll(tenants, tenant -> {
            String s = TenantSchema.validate(tenant.schemaName());
            return "SELECT COALESCE(SUM(e.amount), 0) AS amount FROM " + s + ".expenses e WHERE " + filter.apply(params);
        });
        BigDecimal total = jdbc.queryForObject("SELECT COALESCE(SUM(u.amount), 0) FROM (" + union + ") u",
                BigDecimal.class, params.toArray());
        return total == null ? BigDecimal.ZERO : total;
    }

    @Override
    public Optional<ExpenseDetail> expense(TenantBranch tenant, String expenseId) {
        if (crossTenant.tenantsWith(tenant.id(), "expenses", EXPENSE_COLUMNS).isEmpty()) {
            return Optional.empty();
        }
        String s = TenantSchema.validate(tenant.schemaName());
        return jdbc.query("SELECT CAST(e.id AS text) AS id, CAST(e.store_id AS text) AS store_id, st.name AS store_name, "
                        + "e.category, e.amount, e.expense_date, e.label, e.note, e.payment_method, e.created_at, "
                        + "e.updated_at, COALESCE(NULLIF(btrim(concat_ws(' ', em.first_name, em.last_name)), ''), "
                        + "NULLIF(btrim(concat_ws(' ', pu.first_name, pu.last_name)), '')) AS author "
                        + "FROM " + s + ".expenses e LEFT JOIN " + s + ".stores st ON st.id = e.store_id "
                        + "LEFT JOIN LATERAL (SELECT x.first_name, x.last_name FROM " + s + ".employees x "
                        + "WHERE x.user_id = e.created_by LIMIT 1) em ON TRUE "
                        + "LEFT JOIN public.users pu ON pu.id = e.created_by "
                        + "WHERE e.id = CAST(? AS uuid) AND e.archived_at IS NULL",
                (rs, n) -> new ExpenseDetail(tenant.id(), tenant.name(), rs.getString("id"), rs.getString("store_id"),
                        rs.getString("store_name"), rs.getString("category"),
                        ExpenseLabels.categoryLabel(rs.getString("category")).orElse(null),
                        rs.getBigDecimal("amount"), date(rs, "expense_date"), rs.getString("label"),
                        rs.getString("note"), rs.getString("payment_method"),
                        ExpenseLabels.paymentMethodLabel(rs.getString("payment_method")).orElse(null),
                        rs.getString("author"), instant(rs, "created_at"), instant(rs, "updated_at")),
                expenseId).stream().findFirst();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static Map<String, String> names(List<TenantBranch> tenants) {
        return tenants.stream().collect(Collectors.toMap(TenantBranch::id, TenantBranch::name, (a, b) -> a));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }

    private static LocalDate date(ResultSet rs, String column) throws SQLException {
        Date d = rs.getDate(column);
        return d == null ? null : d.toLocalDate();
    }
}
