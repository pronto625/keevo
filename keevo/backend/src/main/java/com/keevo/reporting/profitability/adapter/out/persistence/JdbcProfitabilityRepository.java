package com.keevo.reporting.profitability.adapter.out.persistence;

import com.keevo.reporting.profitability.domain.port.out.ProfitabilityRepository;
import com.keevo.shared.capability.revenue.RevenueQueryPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * JdbcProfitabilityRepository — SQL adapter for profitability reporting.
 *
 * <p>Uses {@link JdbcTemplate} consistent with {@code DashboardService}.
 * Tenant isolation is handled automatically by the JWT filter setting the
 * PostgreSQL search_path to the tenant schema before each request.
 *
 * <p>Day boundaries use the shop's time zone (WAT, Africa/Lagos) like
 * {@code DashboardService} and {@code ReportController}: "Aujourd'hui" is the
 * local calendar day, not the UTC one.
 *
 * <p>Story 20.9 (R4): every figure reads the revenue source ({@link RevenueQueryPort}); the applied price range of
 * the detail ({@link #findProductCosts}) stays on POS sale lines (it describes the till prices, not revenue).
 *
 * Story 7.4, Task 8.1.
 */
@Repository
public class JdbcProfitabilityRepository implements ProfitabilityRepository {

    /** Shop time zone — same convention as DashboardService / ReportController. */
    static final ZoneId WAT = ZoneId.of("Africa/Lagos");

    /**
     * Per-product aggregation over the revenue lines (20.9). The inner join on {@code products} keeps only lines of a
     * catalogue product: an invoice line without product counts in revenue (see {@link #findRevenueSplit}) but never
     * here (« marge inconnue »).
     */
    private static String productProfitabilitySql(String lines) {
        return "SELECT s.product_id, p.name AS product_name, c.name AS category_name, " +
            "  p.buy_price, p.transport_cost, " +
            "  SUM(s.quantity)::int                             AS units_sold, " +
            "  SUM(s.price_amount)                              AS total_revenue, " +
            "  ROUND(SUM((p.buy_price + p.transport_cost) * s.quantity))::bigint AS total_cost " +
            "FROM " + lines + " s " +
            "JOIN products p ON p.id = s.product_id " +
            "LEFT JOIN categories c ON c.id = p.category_id " +
            "WHERE s.occurred_at >= ? AND s.occurred_at < ? ";
    }

    private static final String PRODUCT_PROFITABILITY_STORE_FILTER = "  AND s.store_id = ?::uuid ";
    private static final String PRODUCT_PROFITABILITY_GROUP =
            "GROUP BY s.product_id, p.name, c.name, p.buy_price, p.transport_cost";

    private final JdbcTemplate jdbc;
    private final RevenueQueryPort revenue;

    public JdbcProfitabilityRepository(JdbcTemplate jdbc, RevenueQueryPort revenue) {
        this.jdbc = jdbc;
        this.revenue = revenue;
    }

    @Override
    public List<RawProfitabilityRow> findRawByPeriod(
            String tenantId, LocalDate from, LocalDate to, UUID storeId) {
        Timestamp tsFrom = Timestamp.from(from.atStartOfDay(WAT).toInstant());
        Timestamp tsTo   = Timestamp.from(to.plusDays(1).atStartOfDay(WAT).toInstant());

        if (storeId == null) {
            String sql = productProfitabilitySql(revenue.current().lines()) + PRODUCT_PROFITABILITY_GROUP;
            return jdbc.query(sql, (rs, rowNum) -> mapProfitabilityRow(rs),
                    tsFrom, tsTo);
        } else {
            String sql = productProfitabilitySql(revenue.storeScoped().lines()) + PRODUCT_PROFITABILITY_STORE_FILTER
                    + PRODUCT_PROFITABILITY_GROUP;
            return jdbc.query(sql, (rs, rowNum) -> mapProfitabilityRow(rs),
                    tsFrom, tsTo, storeId.toString());
        }
    }

    @Override
    public List<RawStorePerformanceRow> findRawStoreByPeriod(
            String tenantId, LocalDate from, LocalDate to) {
        Timestamp tsFrom = Timestamp.from(from.atStartOfDay(WAT).toInstant());
        Timestamp tsTo   = Timestamp.from(to.plusDays(1).atStartOfDay(WAT).toInstant());

        // 20.9/20.14: store-joined → store-scoped relations (documents of each store), resolved once.
        RevenueQueryPort.Relations source = revenue.storeScoped();
        String sql =
            "SELECT " +
            "  s.store_id, " +
            "  st.name                                       AS store_name, " +
            "  COALESCE(SUM(s.total_amount), 0)             AS total_revenue, " +
            "  SUM(s.doc_count)::int                        AS sales_count, " +
            "  COALESCE(SUM(s.total_amount) / NULLIF(SUM(s.doc_count), 0), 0) AS avg_basket, " +
            "  (SELECT s2.product_name " +
            "   FROM " + source.lines() + " s2 " +
            "   WHERE s2.store_id = s.store_id " +
            "     AND s2.occurred_at >= ? AND s2.occurred_at < ? " +
            "   GROUP BY s2.product_name " +
            "   ORDER BY SUM(s2.quantity) DESC LIMIT 1) AS top_product " +
            "FROM " + source.documents() + " s " +
            "JOIN stores st ON st.id = s.store_id " +
            "WHERE s.occurred_at >= ? AND s.occurred_at < ? " +
            "GROUP BY s.store_id, st.name";

        return jdbc.query(sql, (rs, rowNum) -> new RawStorePerformanceRow(
                UUID.fromString(rs.getString("store_id")),
                rs.getString("store_name"),
                rs.getLong("total_revenue"),
                rs.getInt("sales_count"),
                rs.getLong("avg_basket"),
                rs.getString("top_product")
        ), tsFrom, tsTo, tsFrom, tsTo);
    }

    @Override
    public Optional<RawProductCostRow> findProductCosts(UUID productId, UUID storeId) {
        // LEFT JOIN so the row is returned even when the product has no sales yet.
        // With a store filter, only that store's sale lines feed the applied price range.
        String saleItemsJoin = storeId == null
            ? "LEFT JOIN sale_items si ON si.product_id = p.id "
            : "LEFT JOIN sale_items si ON si.product_id = p.id " +
              "  AND si.sale_id IN (SELECT s.id FROM sales s WHERE s.store_id = ?::uuid) ";
        String sql =
            "SELECT p.name AS product_name, " +
            "  c.name AS category_name, " +
            "  p.price, p.buy_price, p.transport_cost, " +
            "  COALESCE(MIN(si.applied_unit_price), 0) AS min_applied, " +
            "  COALESCE(MAX(si.applied_unit_price), 0) AS max_applied, " +
            "  COALESCE(AVG(si.applied_unit_price::float), 0.0) AS avg_applied " +
            "FROM products p " +
            "LEFT JOIN categories c ON c.id = p.category_id " +
            saleItemsJoin +
            "WHERE p.id = ?::uuid " +
            "GROUP BY p.name, c.name, p.price, p.buy_price, p.transport_cost";

        RowMapper<RawProductCostRow> mapper =
            (rs, rowNum) -> new RawProductCostRow(
                        rs.getString("product_name"),
                        rs.getString("category_name"),
                        rs.getInt("price"),
                        rs.getInt("buy_price"),
                        rs.getInt("transport_cost"),
                        rs.getInt("min_applied"),
                        rs.getInt("max_applied"),
                        rs.getDouble("avg_applied")
                );
        List<RawProductCostRow> result = storeId == null
            ? jdbc.query(sql, mapper, productId.toString())
            : jdbc.query(sql, mapper, storeId.toString(), productId.toString());

        return result.isEmpty() ? Optional.empty() : Optional.of(result.get(0));
    }

    @Override
    public List<RawDailyMarginRow> findDailyMarginLast7(
            String tenantId, UUID productId, LocalDate endDate, UUID storeId) {
        LocalDate startDate = endDate.minusDays(6);
        Timestamp tsFrom = Timestamp.from(startDate.atStartOfDay(WAT).toInstant());
        Timestamp tsTo   = Timestamp.from(endDate.plusDays(1).atStartOfDay(WAT).toInstant());

        String sql =
            "SELECT " +
            "  DATE(s.occurred_at AT TIME ZONE 'Africa/Lagos') AS day, " +
            "  COALESCE(ROUND(SUM(s.price_amount - (p.buy_price + p.transport_cost) * s.quantity)), 0)::bigint " +
            "    AS daily_margin " +
            "FROM " + (storeId == null ? revenue.current() : revenue.storeScoped()).lines() + " s " +
            "JOIN products p ON p.id = s.product_id " +
            "WHERE s.product_id = ?::uuid " +
            "  AND s.occurred_at >= ? AND s.occurred_at < ? " +
            (storeId != null ? "  AND s.store_id = ?::uuid " : "") +
            "GROUP BY DATE(s.occurred_at AT TIME ZONE 'Africa/Lagos') " +
            "ORDER BY day ASC";

        RowMapper<RawDailyMarginRow> mapper = (rs, rowNum) -> new RawDailyMarginRow(
                rs.getString("day"),
                rs.getLong("daily_margin"));
        return storeId == null
            ? jdbc.query(sql, mapper, productId.toString(), tsFrom, tsTo)
            : jdbc.query(sql, mapper, productId.toString(), tsFrom, tsTo, storeId.toString());
    }

    @Override
    public Optional<RawTopStoreRow> findTopStoreByUnits(
            String tenantId, UUID productId, LocalDate from, LocalDate to, UUID storeId) {
        Timestamp tsFrom = Timestamp.from(from.atStartOfDay(WAT).toInstant());
        Timestamp tsTo   = Timestamp.from(to.plusDays(1).atStartOfDay(WAT).toInstant());

        String sql =
            "SELECT s.store_id, st.name AS store_name, SUM(s.quantity)::int AS units_sold " +
            "FROM " + revenue.storeScoped().lines() + " s " +
            "JOIN stores st ON st.id = s.store_id " +
            "WHERE s.product_id = ?::uuid " +
            "  AND s.occurred_at >= ? AND s.occurred_at < ? " +
            (storeId != null ? "  AND s.store_id = ?::uuid " : "") +
            "GROUP BY s.store_id, st.name " +
            "ORDER BY units_sold DESC LIMIT 1";

        RowMapper<RawTopStoreRow> mapper = (rs, rowNum) -> new RawTopStoreRow(
                UUID.fromString(rs.getString("store_id")),
                rs.getString("store_name"),
                rs.getInt("units_sold"));
        List<RawTopStoreRow> result = storeId == null
            ? jdbc.query(sql, mapper, productId.toString(), tsFrom, tsTo)
            : jdbc.query(sql, mapper, productId.toString(), tsFrom, tsTo, storeId.toString());
        return result.isEmpty() ? Optional.empty() : Optional.of(result.get(0));
    }

    @Override
    public RawRevenueSplit findRevenueSplit(String tenantId, LocalDate from, LocalDate to, UUID storeId) {
        Timestamp tsFrom = Timestamp.from(from.atStartOfDay(WAT).toInstant());
        Timestamp tsTo   = Timestamp.from(to.plusDays(1).atStartOfDay(WAT).toInstant());
        String docStoreFilter  = storeId == null ? "" : " AND d.store_id = ?::uuid";
        String lineStoreFilter = storeId == null ? "" : " AND s.store_id = ?::uuid";
        // 20.9/20.14: one resolution; a store filter reads the store-scoped relations
        RevenueQueryPort.Relations source = storeId == null ? revenue.current() : revenue.storeScoped();

        // One query: the period's revenue (documents) and its lines split by « margin known » (catalogue product)
        // vs « marge inconnue » (no product, or a product no longer in the catalogue).
        String sql =
            "SELECT " +
            "  (SELECT COALESCE(SUM(d.total_amount), 0) FROM " + source.documents() + " d " +
            "   WHERE d.occurred_at >= ? AND d.occurred_at < ?" + docStoreFilter + ") AS total_revenue, " +
            "  COALESCE(SUM(s.price_amount) FILTER (WHERE p.id IS NOT NULL), 0) AS known_revenue, " +
            "  COALESCE(ROUND(SUM((p.buy_price + p.transport_cost) * s.quantity) FILTER (WHERE p.id IS NOT NULL)), 0)" +
            "::bigint AS known_cost, " +
            "  COALESCE(SUM(s.price_amount) FILTER (WHERE p.id IS NULL), 0) AS unknown_revenue, " +
            "  (COUNT(*) FILTER (WHERE p.id IS NULL))::int AS unknown_lines " +
            "FROM " + source.lines() + " s " +
            "LEFT JOIN products p ON p.id = s.product_id " +
            "WHERE s.occurred_at >= ? AND s.occurred_at < ?" + lineStoreFilter;

        List<Object> args = new ArrayList<>();
        args.add(tsFrom);
        args.add(tsTo);
        if (storeId != null) args.add(storeId.toString());
        args.add(tsFrom);
        args.add(tsTo);
        if (storeId != null) args.add(storeId.toString());

        RawRevenueSplit split = jdbc.queryForObject(sql, (rs, rowNum) -> new RawRevenueSplit(
                rs.getLong("total_revenue"),
                rs.getLong("known_revenue"),
                rs.getLong("known_cost"),
                rs.getLong("unknown_revenue"),
                rs.getInt("unknown_lines")), args.toArray());
        return split != null ? split : RawRevenueSplit.EMPTY;
    }

    // ── private helpers ──────────────────────────────────────────────────────

    private RawProfitabilityRow mapProfitabilityRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new RawProfitabilityRow(
                UUID.fromString(rs.getString("product_id")),
                rs.getString("product_name"),
                rs.getString("category_name"),
                rs.getInt("buy_price"),
                rs.getInt("transport_cost"),
                rs.getInt("units_sold"),
                rs.getLong("total_revenue"),
                rs.getLong("total_cost")
        );
    }
}
