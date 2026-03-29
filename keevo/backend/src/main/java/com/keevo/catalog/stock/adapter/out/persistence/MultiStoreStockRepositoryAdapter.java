package com.keevo.catalog.stock.adapter.out.persistence;

import com.keevo.catalog.stock.domain.model.CrossStoreAvailabilityEntry;
import com.keevo.catalog.stock.domain.model.StockStatus;
import com.keevo.catalog.stock.domain.model.StoreProductStockEntry;
import com.keevo.catalog.stock.domain.model.StoreStockSummary;
import com.keevo.catalog.stock.domain.port.out.MultiStoreStockRepository;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.store.store.domain.model.StoreType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * MultiStoreStockRepositoryAdapter — JdbcTemplate native SQL implementation.
 *
 * <p>Uses JdbcTemplate (not JPA) to avoid cross-module entity imports:
 *   stores     (store/store module)
 *   products   (catalog/product module — shared entity but native SQL is cleaner)
 *   stock_levels (shared entity)
 *
 * <p><b>Important:</b> JdbcTemplate acquires connections directly from the DataSource,
 * bypassing Hibernate's {@code SchemaAwareMultiTenantConnectionProvider} which sets
 * {@code search_path} only for Hibernate-managed connections. Therefore, all SQL
 * templates use {@code %1$s} placeholders filled with the tenant schema from
 * {@link TenantContext} at runtime (same pattern as {@code StoreCountAdapter}).
 *
 * Story 3.2. GoF: Adapter.
 */
@Component
public class MultiStoreStockRepositoryAdapter implements MultiStoreStockRepository {

    private static final String SQL_STORE_OVERVIEW_TPL = """
        SELECT
            s.id           AS store_id,
            s.name         AS store_name,
            s.type         AS store_type,
            COUNT(DISTINCT sl.product_id)                                        AS product_count,
            COALESCE(SUM(sl.quantity * p.price), 0)                             AS total_value_xaf,
            COUNT(CASE WHEN sl.quantity <= COALESCE(NULLIF(p.minimum_threshold, 0), 5) THEN 1 END) AS low_stock_count
        FROM %1$s.stores s
        LEFT JOIN %1$s.stock_levels sl ON sl.store_id = s.id
        LEFT JOIN %1$s.products p     ON p.id = sl.product_id
                                AND p.archived = false
        WHERE s.is_active = true
        GROUP BY s.id, s.name, s.type
        ORDER BY
            CASE WHEN s.type = 'WAREHOUSE' THEN 0 ELSE 1 END ASC,
            s.created_at ASC
        """;

    private static final String SQL_STORE_DETAIL_BASE_TPL = """
        SELECT
            p.id                AS product_id,
            p.name              AS product_name,
            sl.variant_id       AS variant_id,
            NULL                AS variant_label,
            sl.store_id         AS store_id,
            sl.quantity         AS quantity,
            p.minimum_threshold AS minimum_threshold
        FROM %1$s.stock_levels sl
        JOIN %1$s.products p ON p.id = sl.product_id
        WHERE sl.store_id = CAST(? AS uuid)
          AND p.archived = false
        """;

    private static final String SQL_STORE_DETAIL_SORT_LOW_TPL =
        SQL_STORE_DETAIL_BASE_TPL +
        "ORDER BY " +
        "  CASE WHEN sl.quantity <= COALESCE(NULLIF(p.minimum_threshold, 0), 5) AND sl.quantity > 0 THEN 0 " +
        "       WHEN sl.quantity = 0 THEN 1 " +
        "       ELSE 2 END ASC, " +
        "  p.name ASC ";

    private static final String SQL_STORE_DETAIL_SORT_NAME_TPL =
        SQL_STORE_DETAIL_BASE_TPL + "ORDER BY p.name ASC ";

    private static final String SQL_STORE_DETAIL_COUNT_TPL =
        "SELECT COUNT(*) FROM %1$s.stock_levels sl " +
        "JOIN %1$s.products p ON p.id = sl.product_id " +
        "WHERE sl.store_id = CAST(? AS uuid) AND p.archived = false";

    private final JdbcTemplate jdbc;

    public MultiStoreStockRepositoryAdapter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Returns the current tenant schema, validated against the expected format. */
    private String schema() {
        String s = TenantContext.getCurrentTenant();
        if (s == null || !s.matches("^kv_[a-z0-9]{6}$")) {
            throw new IllegalStateException("No valid tenant schema in context");
        }
        return s;
    }

    @Override
    @Transactional(readOnly = true)
    public List<StoreStockSummary> getStoreOverviews() {
        return jdbc.query(String.format(SQL_STORE_OVERVIEW_TPL, schema()), (rs, rowNum) -> new StoreStockSummary(
            UUID.fromString(rs.getString("store_id")),
            rs.getString("store_name"),
            StoreType.valueOf(rs.getString("store_type")),
            rs.getInt("product_count"),
            rs.getLong("total_value_xaf"),
            rs.getInt("low_stock_count")
        ));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<StoreProductStockEntry> getStoreStockDetail(
            UUID storeId, boolean sortLowFirst, Pageable pageable) {

        String s = schema();
        String baseSql  = String.format(
                sortLowFirst ? SQL_STORE_DETAIL_SORT_LOW_TPL : SQL_STORE_DETAIL_SORT_NAME_TPL, s);
        String pagedSql = baseSql + " LIMIT " + pageable.getPageSize()
                                  + " OFFSET " + pageable.getOffset();

        List<StoreProductStockEntry> content = jdbc.query(pagedSql,
            (rs, rowNum) -> {
                String vid = rs.getString("variant_id");
                return new StoreProductStockEntry(
                    UUID.fromString(rs.getString("product_id")),
                    rs.getString("product_name"),
                    vid != null ? UUID.fromString(vid) : null,
                    null, // variantLabel — TODO Story 3.3+ (variants)
                    UUID.fromString(rs.getString("store_id")),
                    rs.getInt("quantity"),
                    rs.getInt("minimum_threshold")
                );
            },
            storeId.toString());

        Long total = jdbc.queryForObject(String.format(SQL_STORE_DETAIL_COUNT_TPL, s), Long.class, storeId.toString());
        return new PageImpl<>(content, pageable, total != null ? total : 0L);
    }

    // ── Story 3.4: cross-store product availability ────────────────────────────

    private static final String SQL_PRODUCT_AVAILABILITY_TPL = """
            SELECT
                s.id                          AS store_id,
                s.name                        AS store_name,
                s.type                        AS store_type,
                COALESCE(sl.quantity, 0)      AS quantity,
                COALESCE(p.minimum_threshold, 0) AS minimum_threshold
            FROM %1$s.stores s
            LEFT JOIN %1$s.stock_levels sl ON sl.store_id = s.id
                                      AND sl.product_id = CAST(? AS uuid)
                                      AND sl.variant_id IS NULL
            LEFT JOIN %1$s.products p      ON p.id = CAST(? AS uuid)
            WHERE s.is_active = true
            ORDER BY s.created_at ASC
            """;

    @Override
    @Transactional(readOnly = true)
    public List<CrossStoreAvailabilityEntry> getProductAvailability(UUID productId) {
        Instant now = Instant.now();
        return jdbc.query(String.format(SQL_PRODUCT_AVAILABILITY_TPL, schema()),
                (rs, rowNum) -> {
                    int qty       = rs.getInt("quantity");
                    int threshold = rs.getInt("minimum_threshold");
                    return new CrossStoreAvailabilityEntry(
                            UUID.fromString(rs.getString("store_id")),
                            rs.getString("store_name"),
                            StoreType.valueOf(rs.getString("store_type")),
                            qty,
                            threshold,
                            threshold > 0 && qty <= threshold,
                            now
                    );
                },
                productId, productId   // two ? placeholders in the SQL
        );
    }
}
