package com.keevo.reporting.dashboard.application.service;

import com.keevo.reporting.dashboard.domain.model.DashboardSummary;
import com.keevo.reporting.dashboard.domain.model.DashboardSummary.*;
import com.keevo.reporting.dashboard.domain.port.in.GetDashboardSummaryUseCase;
import com.keevo.reporting.revenue.domain.port.out.RevenueQueryPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DashboardService — aggregates revenue, stock, store, and employee data
 * into a single DashboardSummary for the OWNER morning dashboard.
 *
 * <p>Uses JdbcTemplate with native SQL for efficient server-side aggregation.
 * Runs within the tenant schema context (set by JWT filter).
 *
 * <p>Story 20.9 (R4): every revenue figure reads the single revenue source ({@link RevenueQueryPort}: COMPLETED
 * sales ∪ counted invoices − credit notes, a bridged sale counted once); transactions are {@code SUM(doc_count)}.
 * Documents carry no store: they are in the all-stores figures, not in the store overviews, which read the
 * store-scoped (sales-only) relations with grouped queries (fixed number of queries whatever the number of stores).
 * Every window ends before tomorrow (WAT).
 *
 * <p>Story 7.1, Task 2.
 */
@Service
@Transactional(readOnly = true)
public class DashboardService implements GetDashboardSummaryUseCase {

    /** Shop time zone (WAT = UTC+1, no DST). Story 7.6 / v1s-16-19. */
    static final ZoneId WAT = ZoneId.of("Africa/Lagos");

    /**
     * v1s-16-19: revenue instant as a WAT wall-clock timestamp. Chart buckets (day, week, month,
     * year) are cut on it, not in the DB session time zone (UTC): a Monday 00:30 WAT sale is
     * Sunday 23:30 UTC and must still count in its own week / day.
     */
    private static final String OCCURRED_WAT = "(r.occurred_at AT TIME ZONE 'Africa/Lagos')";

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final RevenueQueryPort revenue;

    public DashboardService(JdbcTemplate jdbcTemplate, Clock clock, RevenueQueryPort revenue) {
        this.jdbc = jdbcTemplate;
        this.clock = clock;
        this.revenue = revenue;
    }

    @Override
    public DashboardSummary execute(GetDashboardSummaryQuery query) {
        // Story 7.6: all date boundaries computed in WAT (Africa/Lagos = UTC+1, no DST)
        ZoneId wat = WAT;
        LocalDate today = LocalDate.now(clock.withZone(wat));
        Instant startToday       = today.atStartOfDay(wat).toInstant();
        // 20.9: every window ends before tomorrow WAT — a post-dated invoice never inflates today / month / all time
        Instant startTomorrow    = today.plusDays(1).atStartOfDay(wat).toInstant();
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
        ChartWindows windows = new ChartWindows(start30DaysAgo, start12Weeks, start12Months, start5Years,
                startTomorrow);

        // 20.9: one resolution of the tenant's revenue source for the all-stores figures; store overviews read the
        // store-scoped (sales-only) relations
        RevenueQueryPort.Relations source = revenue.current();
        String docs = source.documents();
        String lines = source.lines();

        long todayCA     = sumCA(docs, startToday, startTomorrow);
        long yesterdayCA  = sumCA(docs, startYesterday, startToday);
        long dayBeforeCA  = sumCA(docs, startDayBefore, startYesterday);

        double heroTrend = yesterdayCA > 0
                ? ((double)(todayCA - yesterdayCA) / yesterdayCA) * 100.0
                : 0.0;

        int todaySalesCount       = countSales(docs, startToday, startTomorrow);
        int totalTransactionsMonth = countSales(docs, startOfMonth, startTomorrow);
        int prevMonthTransactions  = countSales(docs, startPrevMonth, startOfMonth);

        long monthCA     = sumCA(docs, startOfMonth, startTomorrow);
        long prevMonthCA = sumCA(docs, startPrevMonth, startOfMonth);

        long averageBasketMonth     = averageBasket(monthCA, totalTransactionsMonth);
        long prevMonthAverageBasket = averageBasket(prevMonthCA, prevMonthTransactions);

        int lowStockCount = countLowStock();

        long weekLinesRevenue = linesRevenue(lines, start7DaysAgo, startTomorrow); // share base, computed once
        List<TopProductEntry> topProducts   =
                getWeeklyProducts(lines, start7DaysAgo, startTomorrow, weekLinesRevenue, 5, false);
        List<TopProductEntry> worstProducts =
                getWeeklyProducts(lines, start7DaysAgo, startTomorrow, weekLinesRevenue, 5, true);
        List<DailyCAEntry> dailyCA          = chart(docs, "day", windows.days(), startTomorrow);
        List<DailyCAEntry> weeklyCA         = chart(docs, "week", windows.weeks(), startTomorrow);
        List<DailyCAEntry> monthlyCA        = chart(docs, "month", windows.months(), startTomorrow);
        List<DailyCAEntry> yearlyCA         = chart(docs, "year", windows.years(), startTomorrow);
        List<StoreOverviewEntry> stores     = getStoreOverviews(revenue.storeScoped().documents(),
                startToday, startYesterday, windows);

        // « Chiffre d'affaires total » — same rules as todayCA (revenue source, corrected total_amount).
        Instant startOfWeek = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                .atStartOfDay(wat).toInstant();
        Instant startOfYear = today.withDayOfYear(1).atStartOfDay(wat).toInstant();
        PeriodTotals periodTotals = new PeriodTotals(
                periodTotal(docs, startOfWeek, startTomorrow),
                new PeriodTotal(monthCA, totalTransactionsMonth),
                periodTotal(docs, startOfYear, startTomorrow),
                periodTotal(docs, null, startTomorrow));

        return new DashboardSummary(
                todayCA, yesterdayCA, dayBeforeCA, heroTrend,
                totalTransactionsMonth, prevMonthTransactions,
                averageBasketMonth, prevMonthAverageBasket,
                lowStockCount, todaySalesCount,
                topProducts, worstProducts, dailyCA,
                weeklyCA, monthlyCA, yearlyCA, stores, periodTotals
        );
    }

    /** Average basket; 0 without transactions or when credit notes leave no positive revenue. */
    static long averageBasket(long revenue, int transactions) {
        return transactions > 0 && revenue > 0 ? revenue / transactions : 0;
    }

    // ── Private aggregation helpers ─────────────────────────────

    /** Revenue in [from, to). */
    private long sumCA(String docs, Instant from, Instant to) {
        Long result = jdbc.queryForObject(
                "SELECT COALESCE(SUM(r.total_amount), 0) FROM " + docs + " r "
                        + "WHERE r.occurred_at >= ? AND r.occurred_at < ?",
                Long.class, Timestamp.from(from), Timestamp.from(to));
        return result != null ? result : 0;
    }

    /** CA and transaction count in [from, to) ({@code from} null = since the beginning), all stores. */
    private PeriodTotal periodTotal(String docs, Instant from, Instant to) {
        String sql = "SELECT COALESCE(SUM(r.total_amount), 0) AS total, COALESCE(SUM(r.doc_count), 0) AS cnt FROM "
                + docs + " r WHERE r.occurred_at < ?" + (from != null ? " AND r.occurred_at >= ?" : "");
        RowMapper<PeriodTotal> mapper = (rs, rowNum) -> new PeriodTotal(rs.getLong("total"), rs.getInt("cnt"));
        PeriodTotal result = from != null
                ? jdbc.queryForObject(sql, mapper, Timestamp.from(to), Timestamp.from(from))
                : jdbc.queryForObject(sql, mapper, Timestamp.from(to));
        return result != null ? result : PeriodTotal.ZERO;
    }

    /** Transactions (sales + invoices) in [from, to). */
    private int countSales(String docs, Instant from, Instant to) {
        Integer result = jdbc.queryForObject(
                "SELECT COALESCE(SUM(r.doc_count), 0)::int FROM " + docs + " r "
                        + "WHERE r.occurred_at >= ? AND r.occurred_at < ?",
                Integer.class, Timestamp.from(from), Timestamp.from(to));
        return result != null ? result : 0;
    }

    private int countLowStock() {
        Integer result = jdbc.queryForObject(
                "SELECT COUNT(DISTINCT sl.product_id) FROM stock_levels sl " +
                "INNER JOIN products p ON p.id = sl.product_id AND p.archived = false AND p.track_stock = true " +
                "WHERE sl.quantity <= COALESCE(NULLIF(p.minimum_threshold, 0), 5)",
                Integer.class);
        return result != null ? result : 0;
    }

    /** Revenue of every line in [from, to), lines without product included: the share base of the product lists. */
    private long linesRevenue(String lines, Instant from, Instant to) {
        Long total = jdbc.queryForObject(
                "SELECT COALESCE(SUM(r.subtotal), 0) FROM " + lines + " r "
                        + "WHERE r.occurred_at >= ? AND r.occurred_at < ?",
                Long.class, Timestamp.from(from), Timestamp.from(to));
        return total != null ? total : 0;
    }

    /**
     * Best (most units first) or worst (fewest units first, only products with units left after credit notes)
     * products over [from, to), one row per product — its catalogue name, else the name on its lines. Lines without
     * product count in the share base ({@code total}) but are never ranked (20.9).
     */
    private List<TopProductEntry> getWeeklyProducts(String lines, Instant from, Instant to, long total, int limit,
                                                    boolean worst) {
        return jdbc.query(
                "SELECT r.product_id, COALESCE(MAX(p.name), MAX(r.product_name)) AS product_name, " +
                "SUM(r.quantity)::int AS units_sold, SUM(r.subtotal) AS revenue " +
                "FROM " + lines + " r LEFT JOIN products p ON p.id = r.product_id " +
                "WHERE r.occurred_at >= ? AND r.occurred_at < ? AND r.product_id IS NOT NULL " +
                "GROUP BY r.product_id " +
                (worst ? "HAVING SUM(r.quantity) > 0 ORDER BY units_sold ASC" : "ORDER BY units_sold DESC") +
                ", r.product_id LIMIT ?",
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
                Timestamp.from(from), Timestamp.from(to), limit);
    }

    /** Lower bounds (WAT midnights) of the daily / weekly / monthly / yearly charts, and their common end. */
    private record ChartWindows(Instant days, Instant weeks, Instant months, Instant years, Instant end) {}

    /** {@code DATE_TRUNC} bucket of the revenue instant on the WAT calendar (weeks start on Monday). */
    private static String bucket(String unit) {
        return "DATE_TRUNC('" + unit + "', " + OCCURRED_WAT + ")";
    }

    /**
     * All-stores chart series bucketed on the WAT calendar (v1s-16-19). {@code unit} is a DATE_TRUNC field
     * (day, week, month, year); the period string keeps the {@code YYYY-MM-DD} contract (week = its Monday, so a
     * week straddling 31 Dec / 1 Jan is one row).
     */
    private List<DailyCAEntry> chart(String docs, String unit, Instant from, Instant to) {
        String bucket = bucket(unit);
        String sql = "SELECT TO_CHAR(" + bucket + ", 'YYYY-MM-DD') AS period, " +
                "COALESCE(SUM(r.total_amount), 0) AS total " +
                "FROM " + docs + " r WHERE r.occurred_at >= ? AND r.occurred_at < ? " +
                "GROUP BY " + bucket + " " +
                "ORDER BY period ASC";
        return jdbc.query(sql, (rs, rowNum) -> new DailyCAEntry(rs.getString("period"), rs.getLong("total")),
                Timestamp.from(from), Timestamp.from(to));
    }

    /** One grouped chart query for every store: store id → its series (ordered by period). */
    private Map<String, List<DailyCAEntry>> chartByStore(String docs, String unit, Instant from, Instant to) {
        String bucket = bucket(unit);
        String sql = "SELECT r.store_id, TO_CHAR(" + bucket + ", 'YYYY-MM-DD') AS period, " +
                "COALESCE(SUM(r.total_amount), 0) AS total " +
                "FROM " + docs + " r WHERE r.store_id IS NOT NULL AND r.occurred_at >= ? AND r.occurred_at < ? " +
                "GROUP BY r.store_id, " + bucket + " " +
                "ORDER BY r.store_id, period ASC";
        Map<String, List<DailyCAEntry>> byStore = new HashMap<>();
        List<StorePoint> points = jdbc.query(sql, (rs, rowNum) -> new StorePoint(
                rs.getString("store_id"), new DailyCAEntry(rs.getString("period"), rs.getLong("total"))),
                Timestamp.from(from), Timestamp.from(to));
        for (StorePoint p : points) {
            byStore.computeIfAbsent(p.storeId(), k -> new ArrayList<>()).add(p.entry());
        }
        return byStore;
    }

    private record StorePoint(String storeId, DailyCAEntry entry) {}

    private record StoreDays(long today, long yesterday) {}

    private List<StoreOverviewEntry> getStoreOverviews(String docs, Instant startToday, Instant startYesterday,
                                                       ChartWindows windows) {
        Map<String, String> stores = new LinkedHashMap<>();
        jdbc.query("SELECT id, name FROM stores WHERE is_active = true",
                (rs, rowNum) -> stores.put(rs.getString("id"), rs.getString("name")));
        if (stores.isEmpty()) {
            return List.of();
        }

        // Today and yesterday, every store in one query (store-scoped: sales only).
        Map<String, StoreDays> days = new HashMap<>();
        jdbc.query(
                "SELECT r.store_id, " +
                "COALESCE(SUM(CASE WHEN r.occurred_at >= ? THEN r.total_amount ELSE 0 END), 0) AS today_ca, " +
                "COALESCE(SUM(CASE WHEN r.occurred_at < ? THEN r.total_amount ELSE 0 END), 0) AS yesterday_ca " +
                "FROM " + docs + " r WHERE r.store_id IS NOT NULL AND r.occurred_at >= ? AND r.occurred_at < ? " +
                "GROUP BY r.store_id",
                (rs, rowNum) -> days.put(rs.getString("store_id"),
                        new StoreDays(rs.getLong("today_ca"), rs.getLong("yesterday_ca"))),
                Timestamp.from(startToday), Timestamp.from(startToday), Timestamp.from(startYesterday),
                Timestamp.from(windows.end()));

        Map<String, Integer> employees = new HashMap<>();
        jdbc.query("SELECT store_id, COUNT(*) AS cnt FROM employees WHERE status = 'ACTIVE' GROUP BY store_id",
                (rs, rowNum) -> employees.put(rs.getString("store_id"), rs.getInt("cnt")));

        Map<String, List<DailyCAEntry>> daily   = chartByStore(docs, "day", windows.days(), windows.end());
        Map<String, List<DailyCAEntry>> weekly  = chartByStore(docs, "week", windows.weeks(), windows.end());
        Map<String, List<DailyCAEntry>> monthly = chartByStore(docs, "month", windows.months(), windows.end());
        Map<String, List<DailyCAEntry>> yearly  = chartByStore(docs, "year", windows.years(), windows.end());

        List<StoreOverviewEntry> overviews = new ArrayList<>(stores.size());
        stores.forEach((storeId, storeName) -> {
            StoreDays d = days.getOrDefault(storeId, new StoreDays(0, 0));
            overviews.add(new StoreOverviewEntry(
                    storeId, storeName, d.today(), d.yesterday(), employees.getOrDefault(storeId, 0),
                    StoreOverviewEntry.computeStatus(d.today(), d.yesterday()),
                    daily.getOrDefault(storeId, List.of()), weekly.getOrDefault(storeId, List.of()),
                    monthly.getOrDefault(storeId, List.of()), yearly.getOrDefault(storeId, List.of())));
        });
        return overviews;
    }
}
