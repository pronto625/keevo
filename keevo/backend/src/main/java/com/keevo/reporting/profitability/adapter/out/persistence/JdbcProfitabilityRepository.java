package com.keevo.reporting.profitability.adapter.out.persistence;

import com.keevo.reporting.profitability.domain.port.out.ProfitabilityRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneOffset;
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
 * Story 7.4, Task 8.1.
 */
@Repository
public class JdbcProfitabilityRepository implements ProfitabilityRepository {

    private static final String PRODUCT_PROFITABILITY_SQL =
            "SELECT si.product_id, p.name AS product_name, c.name AS category_name, " +
            "  p.buy_price, p.transport_cost, " +
            "  SUM(si.quantity)::int                             AS units_sold, " +
            "  SUM(si.applied_unit_price * si.quantity)         AS total_revenue, " +
            "  SUM((p.buy_price + p.transport_cost) * si.quantity) AS total_cost " +
            "FROM sale_items si " +
            "JOIN sales s ON s.id = si.sale_id " +
            "JOIN products p ON p.id = si.product_id " +
            "LEFT JOIN categories c ON c.id = p.category_id " +
            "WHERE s.status = 'COMPLETED' " +
            "  AND s.occurred_at >= ? AND s.occurred_at < ? ";

    private static final String PRODUCT_PROFITABILITY_STORE_FILTER = "  AND s.store_id = ?::uuid ";
    private static final String PRODUCT_PROFITABILITY_GROUP =
            "GROUP BY si.product_id, p.name, c.name, p.buy_price, p.transport_cost";

    private final JdbcTemplate jdbc;

    public JdbcProfitabilityRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<RawProfitabilityRow> findRawByPeriod(
            String tenantId, LocalDate from, LocalDate to, UUID storeId) {
        Timestamp tsFrom = Timestamp.from(from.atStartOfDay().toInstant(ZoneOffset.UTC));
        Timestamp tsTo   = Timestamp.from(to.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC));

        if (storeId == null) {
            String sql = PRODUCT_PROFITABILITY_SQL + PRODUCT_PROFITABILITY_GROUP;
            return jdbc.query(sql, (rs, rowNum) -> mapProfitabilityRow(rs),
                    tsFrom, tsTo);
        } else {
            String sql = PRODUCT_PROFITABILITY_SQL + PRODUCT_PROFITABILITY_STORE_FILTER
                    + PRODUCT_PROFITABILITY_GROUP;
            return jdbc.query(sql, (rs, rowNum) -> mapProfitabilityRow(rs),
                    tsFrom, tsTo, storeId.toString());
        }
    }

    @Override
    public List<RawStorePerformanceRow> findRawStoreByPeriod(
            String tenantId, LocalDate from, LocalDate to) {
        Timestamp tsFrom = Timestamp.from(from.atStartOfDay().toInstant(ZoneOffset.UTC));
        Timestamp tsTo   = Timestamp.from(to.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC));

        String sql =
            "SELECT " +
            "  s.store_id, " +
            "  st.name                                       AS store_name, " +
            "  COALESCE(SUM(s.total_amount), 0)             AS total_revenue, " +
            "  COUNT(s.id)::int                             AS sales_count, " +
            "  COALESCE(SUM(s.total_amount) / NULLIF(COUNT(s.id), 0), 0) AS avg_basket, " +
            "  (SELECT si2.product_name " +
            "   FROM sale_items si2 " +
            "   JOIN sales s2 ON s2.id = si2.sale_id " +
            "   WHERE s2.store_id = s.store_id AND s2.status = 'COMPLETED' " +
            "     AND s2.occurred_at >= ? AND s2.occurred_at < ? " +
            "   GROUP BY si2.product_name " +
            "   ORDER BY SUM(si2.quantity) DESC LIMIT 1) AS top_product " +
            "FROM sales s " +
            "JOIN stores st ON st.id = s.store_id " +
            "WHERE s.status = 'COMPLETED' " +
            "  AND s.occurred_at >= ? AND s.occurred_at < ? " +
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
    public Optional<RawProductCostRow> findProductCosts(UUID productId) {
        // LEFT JOIN so the row is returned even when the product has no sales yet.
        String sql =
            "SELECT p.name AS product_name, " +
            "  c.name AS category_name, " +
            "  p.price, p.buy_price, p.transport_cost, " +
            "  COALESCE(MIN(si.applied_unit_price), 0) AS min_applied, " +
            "  COALESCE(MAX(si.applied_unit_price), 0) AS max_applied, " +
            "  COALESCE(AVG(si.applied_unit_price::float), 0.0) AS avg_applied " +
            "FROM products p " +
            "LEFT JOIN categories c ON c.id = p.category_id " +
            "LEFT JOIN sale_items si ON si.product_id = p.id " +
            "WHERE p.id = ?::uuid " +
            "GROUP BY p.name, c.name, p.price, p.buy_price, p.transport_cost";

        List<RawProductCostRow> result = jdbc.query(sql,
                (rs, rowNum) -> new RawProductCostRow(
                        rs.getString("product_name"),
                        rs.getString("category_name"),
                        rs.getInt("price"),
                        rs.getInt("buy_price"),
                        rs.getInt("transport_cost"),
                        rs.getInt("min_applied"),
                        rs.getInt("max_applied"),
                        rs.getDouble("avg_applied")
                ), productId.toString());

        return result.isEmpty() ? Optional.empty() : Optional.of(result.get(0));
    }

    @Override
    public List<RawDailyMarginRow> findDailyMarginLast7(
            String tenantId, UUID productId, LocalDate endDate) {
        LocalDate startDate = endDate.minusDays(6);
        Timestamp tsFrom = Timestamp.from(startDate.atStartOfDay().toInstant(ZoneOffset.UTC));
        Timestamp tsTo   = Timestamp.from(endDate.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC));

        String sql =
            "SELECT " +
            "  DATE(s.occurred_at) AS day, " +
            "  COALESCE(SUM((si.applied_unit_price - p.buy_price - p.transport_cost) * si.quantity), 0) AS daily_margin " +
            "FROM sale_items si " +
            "JOIN sales s ON s.id = si.sale_id " +
            "JOIN products p ON p.id = si.product_id " +
            "WHERE s.status = 'COMPLETED' " +
            "  AND si.product_id = ?::uuid " +
            "  AND s.occurred_at >= ? AND s.occurred_at < ? " +
            "GROUP BY DATE(s.occurred_at) " +
            "ORDER BY day ASC";

        return jdbc.query(sql, (rs, rowNum) -> new RawDailyMarginRow(
                rs.getString("day"),
                rs.getLong("daily_margin")
        ), productId.toString(), tsFrom, tsTo);
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
