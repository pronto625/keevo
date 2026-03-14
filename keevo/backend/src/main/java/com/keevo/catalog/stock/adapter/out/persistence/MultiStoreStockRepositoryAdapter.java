package com.keevo.catalog.stock.adapter.out.persistence;

import com.keevo.catalog.stock.domain.model.StockStatus;
import com.keevo.catalog.stock.domain.model.StoreProductStockEntry;
import com.keevo.catalog.stock.domain.model.StoreStockSummary;
import com.keevo.catalog.stock.domain.port.out.MultiStoreStockRepository;
import com.keevo.store.store.domain.model.StoreType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

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
 * <p>All SQL runs in the current tenant schema (set by TenantContext ThreadLocal
 * via MultiTenantConnectionProvider — same as all other queries in the app).
 *
 * Story 3.2. GoF: Adapter.
 */
@Component
public class MultiStoreStockRepositoryAdapter implements MultiStoreStockRepository {

    private static final String SQL_STORE_OVERVIEW = """
        SELECT
            s.id           AS store_id,
            s.name         AS store_name,
            s.type         AS store_type,
            COUNT(DISTINCT sl.product_id)                                        AS product_count,
            COALESCE(SUM(sl.quantity * p.price), 0)                             AS total_value_xaf,
            COUNT(CASE WHEN p.minimum_threshold > 0
                        AND sl.quantity <= p.minimum_threshold THEN 1 END)       AS low_stock_count
        FROM stores s
        LEFT JOIN stock_levels sl ON sl.store_id = s.id
        LEFT JOIN products p     ON p.id = sl.product_id
                                AND p.archived = false
        WHERE s.is_active = true
        GROUP BY s.id, s.name, s.type
        ORDER BY
            CASE WHEN s.type = 'WAREHOUSE' THEN 0 ELSE 1 END ASC,
            s.created_at ASC
        """;

    private static final String SQL_STORE_DETAIL_BASE = """
        SELECT
            p.id                AS product_id,
            p.name              AS product_name,
            sl.variant_id       AS variant_id,
            NULL                AS variant_label,
            sl.store_id         AS store_id,
            sl.quantity         AS quantity,
            p.minimum_threshold AS minimum_threshold
        FROM stock_levels sl
        JOIN products p ON p.id = sl.product_id
        WHERE sl.store_id = ?
          AND p.archived = false
        """;

    private static final String SQL_STORE_DETAIL_SORT_LOW =
        SQL_STORE_DETAIL_BASE +
        "ORDER BY " +
        "  CASE WHEN p.minimum_threshold > 0 AND sl.quantity <= p.minimum_threshold AND sl.quantity > 0 THEN 0 " +
        "       WHEN sl.quantity = 0 THEN 1 " +
        "       ELSE 2 END ASC, " +
        "  p.name ASC ";

    private static final String SQL_STORE_DETAIL_SORT_NAME =
        SQL_STORE_DETAIL_BASE + "ORDER BY p.name ASC ";

    private static final String SQL_STORE_DETAIL_COUNT =
        "SELECT COUNT(*) FROM stock_levels sl " +
        "JOIN products p ON p.id = sl.product_id " +
        "WHERE sl.store_id = ? AND p.archived = false";

    private final JdbcTemplate jdbc;

    public MultiStoreStockRepositoryAdapter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public List<StoreStockSummary> getStoreOverviews() {
        return jdbc.query(SQL_STORE_OVERVIEW, (rs, rowNum) -> new StoreStockSummary(
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

        String baseSql  = sortLowFirst ? SQL_STORE_DETAIL_SORT_LOW : SQL_STORE_DETAIL_SORT_NAME;
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

        Long total = jdbc.queryForObject(SQL_STORE_DETAIL_COUNT, Long.class, storeId.toString());
        return new PageImpl<>(content, pageable, total != null ? total : 0L);
    }
}
