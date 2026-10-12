package com.keevo.reporting.dashboard.application.service;

import com.keevo.reporting.dashboard.domain.model.DashboardSummary;
import com.keevo.reporting.dashboard.domain.model.DashboardSummary.*;
import com.keevo.reporting.dashboard.domain.port.in.GetDashboardSummaryUseCase;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.*;
import java.time.temporal.TemporalAdjusters;
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

    /** Shop time zone (WAT = UTC+1, no DST). Story 7.6 / v1s-16-19. */
    static final ZoneId WAT = ZoneId.of("Africa/Lagos");

    /**
     * v1s-16-19: sale instant as a WAT wall-clock timestamp. Chart buckets (day, week, month,
     * year) are cut on it, not in the DB session time zone (UTC): a Monday 00:30 WAT sale is
     * Sunday 23:30 UTC and must still count in its own week / day.
     */
    private static final String OCCURRED_WAT = "(occurred_at AT TIME ZONE 'Africa/Lagos')";

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public DashboardService(JdbcTemplate jdbcTemplate, Clock clock) {
        this.jdbc = jdbcTemplate;
        this.clock = clock;
    }

    @Override
    public DashboardSummary execute(GetDashboardSummaryQuery query) {
        // Story 7.6: all date boundaries computed in WAT (Africa/Lagos = UTC+1, no DST)
        ZoneId wat = WAT;
        LocalDate today = LocalDate.now(clock.withZone(wat));
        Instant startToday       = today.atStartOfDay(wat).toInstant();
        Instant startYesterday   = today.minusDays(1).atStartOfDay(wat).toInstant();
        Instant startDayBefore   = today.minusDays(2).atStartOfDay(wat).toInstant();
        Instant startOfMonth     = today.withDayOfMonth(1).atStartOfDay(wat).toInstant();
        Instant startPrevMonth   = today.minusMonths(1).withDayOfMonth(1).atStartOfDay(wat).toInstant();
        Instant start30DaysAgo   = today.minusDays(29).atStartOfDay(wat).toInstant();

        // Rolling 7-day window for top/worst products (matches UI label "7j")
        Instant start7DaysAgo = today.minusDays(6).atStartOfDay(wat).toInstant();

        // Chart windows (v1s-16-19): computed in WAT from the clock, not with NOW() in SQL.
        Instant start12Weeks  = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                .minusWeeks(11).atStartOfDay(wat).toInstant();
        Instant start12Months = today.withDayOfMonth(1).minusMonths(11).atStartOfDay(wat).toInstant();
        Instant start5Years   = today.withDayOfYear(1).minusYears(4).atStartOfDay(wat).toInstant();
        ChartWindows windows = new ChartWindows(start30DaysAgo, start12Weeks, start12Months, start5Years);

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
        List<DailyCAEntry> dailyCA          = chart("day", windows.days(), null);
        List<DailyCAEntry> weeklyCA         = chart("week", windows.weeks(), null);
        List<DailyCAEntry> monthlyCA        = chart("month", windows.months(), null);
        List<DailyCAEntry> yearlyCA         = chart("year", windows.years(), null);
        List<StoreOverviewEntry> stores     = getStoreOverviews(startToday, startYesterday, windows);

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

    /** Lower bounds (WAT midnights) of the daily / weekly / monthly / yearly charts. */
    private record ChartWindows(Instant days, Instant weeks, Instant months, Instant years) {}

    /**
     * Chart series bucketed on the WAT calendar (v1s-16-19). {@code unit} is a DATE_TRUNC field
     * (day, week, month, year); weeks start on Monday (PostgreSQL ISO week). The period string
     * keeps the {@code YYYY-MM-DD} contract (week = its Monday, so a week straddling 31 Dec /
     * 1 Jan is one row). {@code storeId} null = all stores.
     */
    private List<DailyCAEntry> chart(String unit, Instant from, String storeId) {
        String bucket = "DATE_TRUNC('" + unit + "', " + OCCURRED_WAT + ")";
        String sql = "SELECT TO_CHAR(" + bucket + ", 'YYYY-MM-DD') AS period, " +
                "COALESCE(SUM(total_amount), 0) AS total " +
                "FROM sales WHERE status = 'COMPLETED' " +
                (storeId != null ? "AND store_id = ?::uuid " : "") +
                "AND occurred_at >= ? " +
                "GROUP BY " + bucket + " " +
                "ORDER BY period ASC";
        RowMapper<DailyCAEntry> mapper = (rs, rowNum) -> new DailyCAEntry(
                rs.getString("period"),
                rs.getLong("total"));
        return storeId != null
                ? jdbc.query(sql, mapper, storeId, Timestamp.from(from))
                : jdbc.query(sql, mapper, Timestamp.from(from));
    }

    private List<StoreOverviewEntry> getStoreOverviews(Instant startToday, Instant startYesterday, ChartWindows windows) {
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

            List<DailyCAEntry> storeDaily   = chart("day", windows.days(), storeId);
            List<DailyCAEntry> storeWeekly  = chart("week", windows.weeks(), storeId);
            List<DailyCAEntry> storeMonthly = chart("month", windows.months(), storeId);
            List<DailyCAEntry> storeYearly  = chart("year", windows.years(), storeId);

            overviews.add(new StoreOverviewEntry(
                    storeId, storeName, tCA, yCA, ec,
                    StoreOverviewEntry.computeStatus(tCA, yCA),
                    storeDaily, storeWeekly, storeMonthly, storeYearly
            ));
        }

        return overviews;
    }
}
