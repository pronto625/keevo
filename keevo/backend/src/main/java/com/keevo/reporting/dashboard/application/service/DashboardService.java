package com.keevo.reporting.dashboard.application.service;

import com.keevo.reporting.dashboard.domain.model.DashboardSummary;
import com.keevo.reporting.dashboard.domain.model.DashboardSummary.*;
import com.keevo.reporting.dashboard.domain.port.in.GetDashboardSummaryUseCase;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * DashboardService — aggregates sales, stock, store, and employee data
 * into a single DashboardSummary for the OWNER morning dashboard.
 *
 * <p>Uses JdbcTemplate with native SQL for efficient server-side aggregation.
 * Runs within the tenant schema context (set by JWT filter).
 *
 * <p>Story 7.1, Task 2.
 */
@Service
@Transactional(readOnly = true)
public class DashboardService implements GetDashboardSummaryUseCase {

    private final JdbcTemplate jdbc;

    public DashboardService(JdbcTemplate jdbcTemplate) {
        this.jdbc = jdbcTemplate;
    }

    @Override
    public DashboardSummary execute(GetDashboardSummaryQuery query) {
        // Story 7.6: all date boundaries computed in WAT (Africa/Lagos = UTC+1, no DST)
        ZoneId wat = ZoneId.of("Africa/Lagos");
        LocalDate today = LocalDate.now(wat);
        Instant startToday       = today.atStartOfDay(wat).toInstant();
        Instant startYesterday   = today.minusDays(1).atStartOfDay(wat).toInstant();
        Instant startDayBefore   = today.minusDays(2).atStartOfDay(wat).toInstant();
        Instant startOfMonth     = today.withDayOfMonth(1).atStartOfDay(wat).toInstant();
        Instant startPrevMonth   = today.minusMonths(1).withDayOfMonth(1).atStartOfDay(wat).toInstant();
        Instant start30DaysAgo   = today.minusDays(29).atStartOfDay(wat).toInstant();

        // Rolling 7-day window for top/worst products (matches UI label "7j")
        Instant start7DaysAgo = today.minusDays(6).atStartOfDay(wat).toInstant();

        long todayCA     = sumCA(startToday, null);
        long yesterdayCA  = sumCA(startYesterday, startToday);
        long dayBeforeCA  = sumCA(startDayBefore, startYesterday);

        double heroTrend = yesterdayCA > 0
                ? ((double)(todayCA - yesterdayCA) / yesterdayCA) * 100.0
                : 0.0;

        int todaySalesCount       = countSales(startToday, null);
        int totalTransactionsMonth = countSales(startOfMonth, null);
        int prevMonthTransactions  = countSales(startPrevMonth, startOfMonth);

        long monthCA     = sumCA(startOfMonth, null);
        long prevMonthCA = sumCA(startPrevMonth, startOfMonth);

        long averageBasketMonth     = totalTransactionsMonth > 0
                ? monthCA / totalTransactionsMonth : 0;
        long prevMonthAverageBasket = prevMonthTransactions > 0
                ? prevMonthCA / prevMonthTransactions : 0;

        int lowStockCount = countLowStock();

        List<TopProductEntry> topProducts   = getWeeklyTopProducts(start7DaysAgo, 5);
        List<TopProductEntry> worstProducts = getWeeklyWorstProducts(start7DaysAgo, 5);
        List<DailyCAEntry> dailyCA          = getDailyCA(start30DaysAgo);
        List<DailyCAEntry> weeklyCA         = getWeeklyCA();
        List<DailyCAEntry> monthlyCA        = getMonthlyCA();
        List<DailyCAEntry> yearlyCA         = getYearlyCA();
        List<StoreOverviewEntry> stores     = getStoreOverviews(startToday, startYesterday, start30DaysAgo);

        return new DashboardSummary(
                todayCA, yesterdayCA, dayBeforeCA, heroTrend,
                totalTransactionsMonth, prevMonthTransactions,
                averageBasketMonth, prevMonthAverageBasket,
                lowStockCount, todaySalesCount,
                topProducts, worstProducts, dailyCA,
                weeklyCA, monthlyCA, yearlyCA, stores
        );
    }

    // ── Private aggregation helpers ─────────────────────────────

    private long sumCA(Instant from, Instant to) {
        if (to == null) {
            Long result = jdbc.queryForObject(
                    "SELECT COALESCE(SUM(total_amount), 0) FROM sales " +
                    "WHERE status = 'COMPLETED' AND occurred_at >= ?",
                    Long.class, java.sql.Timestamp.from(from));
            return result != null ? result : 0;
        }
        Long result = jdbc.queryForObject(
                "SELECT COALESCE(SUM(total_amount), 0) FROM sales " +
                "WHERE status = 'COMPLETED' AND occurred_at >= ? AND occurred_at < ?",
                Long.class,
                java.sql.Timestamp.from(from), java.sql.Timestamp.from(to));
        return result != null ? result : 0;
    }

    private int countSales(Instant from, Instant to) {
        if (to == null) {
            Integer result = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM sales " +
                    "WHERE status = 'COMPLETED' AND occurred_at >= ?",
                    Integer.class, java.sql.Timestamp.from(from));
            return result != null ? result : 0;
        }
        Integer result = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sales " +
                "WHERE status = 'COMPLETED' AND occurred_at >= ? AND occurred_at < ?",
                Integer.class,
                java.sql.Timestamp.from(from), java.sql.Timestamp.from(to));
        return result != null ? result : 0;
    }

    private int countLowStock() {
        Integer result = jdbc.queryForObject(
                "SELECT COUNT(DISTINCT sl.product_id) FROM stock_levels sl " +
                "INNER JOIN products p ON p.id = sl.product_id AND p.archived = false " +
                "WHERE sl.quantity <= COALESCE(NULLIF(p.minimum_threshold, 0), 5)",
                Integer.class);
        return result != null ? result : 0;
    }

    private List<TopProductEntry> getWeeklyTopProducts(Instant startOfWeek, int limit) {
        // Total weekly revenue for share calculation
        Long totalRevenue = jdbc.queryForObject(
                "SELECT COALESCE(SUM(si.subtotal), 0) FROM sale_items si " +
                "INNER JOIN sales s ON si.sale_id = s.id " +
                "WHERE s.status = 'COMPLETED' AND s.occurred_at >= ?",
                Long.class, java.sql.Timestamp.from(startOfWeek));
        long total = totalRevenue != null ? totalRevenue : 0;

        return jdbc.query(
                "SELECT si.product_id, si.product_name, " +
                "SUM(si.quantity) AS units_sold, SUM(si.subtotal) AS revenue " +
                "FROM sale_items si INNER JOIN sales s ON si.sale_id = s.id " +
                "WHERE s.status = 'COMPLETED' AND s.occurred_at >= ? " +
                "GROUP BY si.product_id, si.product_name " +
                "ORDER BY units_sold DESC LIMIT ?",
                (rs, rowNum) -> {
                    long revenue = rs.getLong("revenue");
                    return new TopProductEntry(
                            rs.getString("product_id"),
                            rs.getString("product_name"),
                            rs.getInt("units_sold"),
                            revenue,
                            total > 0 ? (double) revenue / total * 100.0 : 0.0
                    );
                },
                java.sql.Timestamp.from(startOfWeek), limit);
    }

    private List<TopProductEntry> getWeeklyWorstProducts(Instant startOfWeek, int limit) {
        Long totalRevenue = jdbc.queryForObject(
                "SELECT COALESCE(SUM(si.subtotal), 0) FROM sale_items si " +
                "INNER JOIN sales s ON si.sale_id = s.id " +
                "WHERE s.status = 'COMPLETED' AND s.occurred_at >= ?",
                Long.class, java.sql.Timestamp.from(startOfWeek));
        long total = totalRevenue != null ? totalRevenue : 0;

        return jdbc.query(
                "SELECT si.product_id, si.product_name, " +
                "SUM(si.quantity) AS units_sold, SUM(si.subtotal) AS revenue " +
                "FROM sale_items si INNER JOIN sales s ON si.sale_id = s.id " +
                "WHERE s.status = 'COMPLETED' AND s.occurred_at >= ? " +
                "GROUP BY si.product_id, si.product_name " +
                "ORDER BY units_sold ASC LIMIT ?",
                (rs, rowNum) -> {
                    long revenue = rs.getLong("revenue");
                    return new TopProductEntry(
                            rs.getString("product_id"),
                            rs.getString("product_name"),
                            rs.getInt("units_sold"),
                            revenue,
                            total > 0 ? (double) revenue / total * 100.0 : 0.0
                    );
                },
                java.sql.Timestamp.from(startOfWeek), limit);
    }

    private List<DailyCAEntry> getDailyCA(Instant start30DaysAgo) {
        return jdbc.query(
                "SELECT DATE(occurred_at) AS day, COALESCE(SUM(total_amount), 0) AS total " +
                "FROM sales WHERE status = 'COMPLETED' AND occurred_at >= ? " +
                "GROUP BY DATE(occurred_at) ORDER BY day ASC",
                (rs, rowNum) -> new DailyCAEntry(
                        rs.getString("day"),
                        rs.getLong("total")
                ),
                java.sql.Timestamp.from(start30DaysAgo));
    }

    private List<DailyCAEntry> getWeeklyCA() {
        return jdbc.query(
                "SELECT TO_CHAR(DATE_TRUNC('week', occurred_at), 'YYYY-MM-DD') AS period, " +
                "COALESCE(SUM(total_amount), 0) AS total " +
                "FROM sales WHERE status = 'COMPLETED' " +
                "AND occurred_at >= DATE_TRUNC('week', NOW()) - INTERVAL '11 weeks' " +
                "GROUP BY DATE_TRUNC('week', occurred_at) " +
                "ORDER BY period ASC",
                (rs, rowNum) -> new DailyCAEntry(
                        rs.getString("period"),
                        rs.getLong("total")
                ));
    }

    private List<DailyCAEntry> getMonthlyCA() {
        return jdbc.query(
                "SELECT TO_CHAR(DATE_TRUNC('month', occurred_at), 'YYYY-MM-DD') AS period, " +
                "COALESCE(SUM(total_amount), 0) AS total " +
                "FROM sales WHERE status = 'COMPLETED' " +
                "AND occurred_at >= DATE_TRUNC('month', NOW()) - INTERVAL '11 months' " +
                "GROUP BY DATE_TRUNC('month', occurred_at) " +
                "ORDER BY period ASC",
                (rs, rowNum) -> new DailyCAEntry(
                        rs.getString("period"),
                        rs.getLong("total")
                ));
    }

    private List<DailyCAEntry> getYearlyCA() {
        return jdbc.query(
                "SELECT TO_CHAR(DATE_TRUNC('year', occurred_at), 'YYYY-MM-DD') AS period, " +
                "COALESCE(SUM(total_amount), 0) AS total " +
                "FROM sales WHERE status = 'COMPLETED' " +
                "AND occurred_at >= DATE_TRUNC('year', NOW()) - INTERVAL '4 years' " +
                "GROUP BY DATE_TRUNC('year', occurred_at) " +
                "ORDER BY period ASC",
                (rs, rowNum) -> new DailyCAEntry(
                        rs.getString("period"),
                        rs.getLong("total")
                ));
    }

    private List<StoreOverviewEntry> getStoreOverviews(Instant startToday, Instant startYesterday, Instant start30DaysAgo) {
        // Get all active stores
        List<StoreOverviewEntry> overviews = new ArrayList<>();

        var stores = jdbc.query(
                "SELECT id, name FROM stores WHERE is_active = true",
                (rs, rowNum) -> new Object[] { rs.getString("id"), rs.getString("name") });

        for (var store : stores) {
            String storeId   = (String) store[0];
            String storeName = (String) store[1];

            // Today CA
            Long todayCA = jdbc.queryForObject(
                    "SELECT COALESCE(SUM(total_amount), 0) FROM sales " +
                    "WHERE status = 'COMPLETED' AND store_id = ?::uuid AND occurred_at >= ?",
                    Long.class, storeId, java.sql.Timestamp.from(startToday));

            // Yesterday CA
            Long yesterdayCA = jdbc.queryForObject(
                    "SELECT COALESCE(SUM(total_amount), 0) FROM sales " +
                    "WHERE status = 'COMPLETED' AND store_id = ?::uuid " +
                    "AND occurred_at >= ? AND occurred_at < ?",
                    Long.class, storeId,
                    java.sql.Timestamp.from(startYesterday),
                    java.sql.Timestamp.from(startToday));

            // Employee count
            Integer empCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM employees WHERE store_id = ?::uuid AND status = 'ACTIVE'",
                    Integer.class, storeId);

            long tCA = todayCA != null ? todayCA : 0;
            long yCA = yesterdayCA != null ? yesterdayCA : 0;
            int ec   = empCount != null ? empCount : 0;

            List<DailyCAEntry> storeDaily   = getStoreDailyCA(storeId, start30DaysAgo);
            List<DailyCAEntry> storeWeekly  = getStoreWeeklyCA(storeId);
            List<DailyCAEntry> storeMonthly = getStoreMonthlyCA(storeId);
            List<DailyCAEntry> storeYearly  = getStoreYearlyCA(storeId);

            overviews.add(new StoreOverviewEntry(
                    storeId, storeName, tCA, yCA, ec,
                    StoreOverviewEntry.computeStatus(tCA, yCA),
                    storeDaily, storeWeekly, storeMonthly, storeYearly
            ));
        }

        return overviews;
    }

    private List<DailyCAEntry> getStoreDailyCA(String storeId, Instant start30DaysAgo) {
        return jdbc.query(
                "SELECT DATE(occurred_at) AS day, COALESCE(SUM(total_amount), 0) AS total " +
                "FROM sales WHERE status = 'COMPLETED' AND store_id = ?::uuid AND occurred_at >= ? " +
                "GROUP BY DATE(occurred_at) ORDER BY day ASC",
                (rs, rowNum) -> new DailyCAEntry(
                        rs.getString("day"),
                        rs.getLong("total")
                ),
                storeId, java.sql.Timestamp.from(start30DaysAgo));
    }

    private List<DailyCAEntry> getStoreWeeklyCA(String storeId) {
        return jdbc.query(
                "SELECT TO_CHAR(DATE_TRUNC('week', occurred_at), 'YYYY-MM-DD') AS period, " +
                "COALESCE(SUM(total_amount), 0) AS total " +
                "FROM sales WHERE status = 'COMPLETED' AND store_id = ?::uuid " +
                "AND occurred_at >= DATE_TRUNC('week', NOW()) - INTERVAL '11 weeks' " +
                "GROUP BY DATE_TRUNC('week', occurred_at) " +
                "ORDER BY period ASC",
                (rs, rowNum) -> new DailyCAEntry(
                        rs.getString("period"),
                        rs.getLong("total")
                ),
                storeId);
    }

    private List<DailyCAEntry> getStoreMonthlyCA(String storeId) {
        return jdbc.query(
                "SELECT TO_CHAR(DATE_TRUNC('month', occurred_at), 'YYYY-MM-DD') AS period, " +
                "COALESCE(SUM(total_amount), 0) AS total " +
                "FROM sales WHERE status = 'COMPLETED' AND store_id = ?::uuid " +
                "AND occurred_at >= DATE_TRUNC('month', NOW()) - INTERVAL '11 months' " +
                "GROUP BY DATE_TRUNC('month', occurred_at) " +
                "ORDER BY period ASC",
                (rs, rowNum) -> new DailyCAEntry(
                        rs.getString("period"),
                        rs.getLong("total")
                ),
                storeId);
    }

    private List<DailyCAEntry> getStoreYearlyCA(String storeId) {
        return jdbc.query(
                "SELECT TO_CHAR(DATE_TRUNC('year', occurred_at), 'YYYY-MM-DD') AS period, " +
                "COALESCE(SUM(total_amount), 0) AS total " +
                "FROM sales WHERE status = 'COMPLETED' AND store_id = ?::uuid " +
                "AND occurred_at >= DATE_TRUNC('year', NOW()) - INTERVAL '4 years' " +
                "GROUP BY DATE_TRUNC('year', occurred_at) " +
                "ORDER BY period ASC",
                (rs, rowNum) -> new DailyCAEntry(
                        rs.getString("period"),
                        rs.getLong("total")
                ),
                storeId);
    }
}
