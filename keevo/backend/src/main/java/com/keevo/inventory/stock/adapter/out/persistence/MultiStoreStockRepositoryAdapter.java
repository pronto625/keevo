package com.keevo.inventory.stock.adapter.out.persistence;

import com.keevo.inventory.stock.domain.model.CrossStoreAvailabilityEntry;
import com.keevo.inventory.stock.domain.model.StoreProductStockEntry;
import com.keevo.inventory.stock.domain.model.StoreStockSummary;
import com.keevo.inventory.stock.domain.port.out.MultiStoreStockRepository;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.infrastructure.persistence.TenantSchema;
import com.keevo.store.api.StoreType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
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
 * <p>Story 16.13 (reprise 10/11/2026): every read takes an optional single-store filter (bound parameter), and the
 * overview is set-based — {@code stock_levels} aggregated per store first (O(stock rows), index
 * {@code (store_id, product_id)} when filtered), then joined to the stores — instead of the stores × products cross
 * join it used to build before reaching {@code stock_levels}. Values are summed in 64 bits.
 *
 * Story 3.2. GoF: Adapter.
 */
@Component
public class MultiStoreStockRepositoryAdapter implements MultiStoreStockRepository {

    /**
     * One row per active store. J29: {@code low_stock_count} (Catalogue badge, Story 8.1 AC8) counts only products
     * actually stocked in the store (a stock row exists) at or below the threshold, negative included — a product never
     * stocked there is shown at 0 in the store detail but is not an alert. {@code product_count} is the number of
     * products actually stocked in the store. Story 17.12: products without stock tracking are left out (no count, no
     * value, no alert). {@code %2$s}: the optional store filter of the aggregate, {@code %3$s}: the same on the stores.
     */
    static final String SQL_STORE_OVERVIEW_TPL = """
        WITH per_store AS (
            SELECT
                sl.store_id,
                COUNT(*)                                                                 AS product_count, -- uq (product_id, store_id)
                SUM(CAST(sl.quantity AS bigint) * p.price)                               AS total_value_xaf,
                COUNT(*) FILTER (WHERE sl.quantity <= COALESCE(NULLIF(p.minimum_threshold, 0), 5)) AS low_stock_count
            FROM %1$s.stock_levels sl
            JOIN %1$s.products p ON p.id = sl.product_id AND p.archived = false AND p.track_stock = true
            %2$s
            GROUP BY sl.store_id
        )
        SELECT
            s.id                                AS store_id,
            s.name                              AS store_name,
            s.type                              AS store_type,
            COALESCE(ps.product_count, 0)       AS product_count,
            COALESCE(ps.total_value_xaf, 0)     AS total_value_xaf,
            COALESCE(ps.low_stock_count, 0)     AS low_stock_count
        FROM %1$s.stores s
        LEFT JOIN per_store ps ON ps.store_id = s.id
        WHERE s.is_active = true
        %3$s
        ORDER BY
            CASE WHEN s.type = 'WAREHOUSE' THEN 0 ELSE 1 END ASC,
            s.created_at ASC
        """;

    static final String SQL_OVERVIEW_LEVEL_STORE_FILTER = "WHERE sl.store_id = CAST(? AS uuid)";
    static final String SQL_OVERVIEW_STORE_FILTER = "AND s.id = CAST(? AS uuid)";

    /**
     * Store detail: starts from the (non-archived) catalogue and LEFT JOINs the store's stock
     * levels, so a product never stocked in the store is listed with quantity 0 instead of
     * being absent. Single bind parameter: the store id (in the join condition).
     */
    static final String SQL_STORE_DETAIL_FROM_TPL = """
        FROM %1$s.products p
        LEFT JOIN %1$s.stock_levels sl ON sl.product_id = p.id
                                      AND sl.store_id = CAST(? AS uuid)
        WHERE p.archived = false AND p.track_stock = true
        """;

    /**
     * Story 16.13 (reprise) — the page and its total in ONE statement: {@code COUNT(*) OVER ()} is computed over the
     * filtered rows before the (bounded, top-N) sort and the LIMIT, so the catalogue is scanned once per page instead of
     * twice (page + separate {@code COUNT(*)}).
     */
    static final String SQL_STORE_DETAIL_BASE_TPL = """
        SELECT
            p.id                     AS product_id,
            p.name                   AS product_name,
            sl.variant_id            AS variant_id,
            NULL                     AS variant_label,
            COALESCE(sl.quantity, 0) AS quantity,
            p.minimum_threshold      AS minimum_threshold,
            COUNT(*) OVER ()         AS total_count
        """ + SQL_STORE_DETAIL_FROM_TPL;

    /** Low-stock filter (lowOnly) — same threshold rule as the overview's low_stock_count. */
    static final String SQL_LOW_FILTER =
        "  AND COALESCE(sl.quantity, 0) <= COALESCE(NULLIF(p.minimum_threshold, 0), 5)\n";

    static final String SQL_STORE_DETAIL_SORT_LOW =
        "ORDER BY " +
        "  CASE WHEN COALESCE(sl.quantity, 0) <= COALESCE(NULLIF(p.minimum_threshold, 0), 5)" +
        "            AND COALESCE(sl.quantity, 0) > 0 THEN 0 " +
        "       WHEN COALESCE(sl.quantity, 0) <= 0 THEN 1 " +
        "       ELSE 2 END ASC, " +
        "  p.name ASC ";

    static final String SQL_STORE_DETAIL_SORT_NAME = "ORDER BY p.name ASC ";

    /** Only for a page past the end (no row carries the window total). */
    static final String SQL_STORE_DETAIL_COUNT_TPL =
        "SELECT COUNT(*) " + SQL_STORE_DETAIL_FROM_TPL;

    private final JdbcTemplate jdbc;

    public MultiStoreStockRepositoryAdapter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Returns the current tenant schema, validated against the expected format. */
    private String schema() {
        String s = TenantContext.getCurrentTenant();
        if (!TenantSchema.isValid(s)) {
            throw new IllegalStateException("No valid tenant schema in context");
        }
        return s;
    }

    @Override
    @Transactional(readOnly = true)
    public List<StoreStockSummary> getStoreOverviews(UUID onlyStoreId) {
        String s = TenantSchema.validate(schema());
        boolean filtered = onlyStoreId != null;
        String sql = String.format(SQL_STORE_OVERVIEW_TPL, s,
                filtered ? SQL_OVERVIEW_LEVEL_STORE_FILTER : "", filtered ? SQL_OVERVIEW_STORE_FILTER : "");
        RowMapper<StoreStockSummary> mapper = (rs, rowNum) -> new StoreStockSummary(
            UUID.fromString(rs.getString("store_id")),
            rs.getString("store_name"),
            StoreType.valueOf(rs.getString("store_type")),
            rs.getInt("product_count"),
            rs.getLong("total_value_xaf"),
            rs.getInt("low_stock_count")
        );
        return filtered
                ? jdbc.query(sql, mapper, onlyStoreId.toString(), onlyStoreId.toString())
                : jdbc.query(sql, mapper);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<StoreProductStockEntry> getStoreStockDetail(
            UUID storeId, boolean sortLowFirst, boolean lowOnly, Pageable pageable) {

        String s = TenantSchema.validate(schema()); // schema() already throws IllegalStateException when invalid
        String lowFilter = lowOnly ? SQL_LOW_FILTER : "";
        String pagedSql = String.format(SQL_STORE_DETAIL_BASE_TPL, s)
                + lowFilter
                + (sortLowFirst ? SQL_STORE_DETAIL_SORT_LOW : SQL_STORE_DETAIL_SORT_NAME)
                + " LIMIT " + pageable.getPageSize()
                + " OFFSET " + pageable.getOffset();

        long[] total = {-1L};
        List<StoreProductStockEntry> content = jdbc.query(pagedSql,
            (rs, rowNum) -> {
                total[0] = rs.getLong("total_count");
                String vid = rs.getString("variant_id");
                return new StoreProductStockEntry(
                    UUID.fromString(rs.getString("product_id")),
                    rs.getString("product_name"),
                    vid != null ? UUID.fromString(vid) : null,
                    null, // variantLabel — TODO Story 3.3+ (variants)
                    storeId,
                    rs.getInt("quantity"),
                    rs.getInt("minimum_threshold")
                );
            },
            storeId.toString());

        if (total[0] < 0) {
            // Empty page: the first page has nothing at all; a page past the end needs the real total (rare).
            if (pageable.getOffset() == 0) {
                total[0] = 0L;
            } else {
                String countSql = String.format(SQL_STORE_DETAIL_COUNT_TPL, TenantSchema.validate(s)) + lowFilter;
                Long counted = jdbc.queryForObject(countSql, Long.class, storeId.toString());
                total[0] = counted != null ? counted : 0L;
            }
        }
        return new PageImpl<>(content, pageable, total[0]);
    }

    // ── Story 3.4: cross-store product availability ────────────────────────────

    static final String SQL_PRODUCT_AVAILABILITY_TPL = """
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
            %2$s
            ORDER BY s.created_at ASC
            """;

    @Override
    @Transactional(readOnly = true)
    public List<CrossStoreAvailabilityEntry> getProductAvailability(UUID productId, UUID onlyStoreId) {
        Instant now = Instant.now();
        boolean filtered = onlyStoreId != null;
        String sql = String.format(SQL_PRODUCT_AVAILABILITY_TPL, TenantSchema.validate(schema()),
                filtered ? SQL_OVERVIEW_STORE_FILTER : "");
        Object[] args = filtered
                ? new Object[]{productId, productId, onlyStoreId.toString()}
                : new Object[]{productId, productId};
        return jdbc.query(sql,
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
                args
        );
    }
}
