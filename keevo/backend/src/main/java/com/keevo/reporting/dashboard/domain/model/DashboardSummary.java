package com.keevo.reporting.dashboard.domain.model;

import java.util.List;

/**
 * DashboardSummary — aggregate dashboard data for OWNER morning view.
 * Story 7.1, Task 2.
 */
public record DashboardSummary(
        long todayCA,
        long yesterdayCA,
        long dayBeforeYesterdayCA,
        double heroTrendPercent,
        int totalTransactionsMonth,
        int prevMonthTransactions,
        long averageBasketMonth,
        long prevMonthAverageBasket,
        int lowStockCount,
        int todaySalesCount,
        List<TopProductEntry> weeklyTopProducts,
        List<TopProductEntry> weeklyWorstProducts,
        List<DailyCAEntry> dailyCALast30,
        List<DailyCAEntry> weeklyCA,
        List<DailyCAEntry> monthlyCA,
        List<DailyCAEntry> yearlyCA,
        List<StoreOverviewEntry> storeOverviews,
        PeriodTotals periodTotals
) {

    /** Without period totals (callers predating the « Chiffre d'affaires total » card). */
    public DashboardSummary(
            long todayCA, long yesterdayCA, long dayBeforeYesterdayCA, double heroTrendPercent,
            int totalTransactionsMonth, int prevMonthTransactions,
            long averageBasketMonth, long prevMonthAverageBasket,
            int lowStockCount, int todaySalesCount,
            List<TopProductEntry> weeklyTopProducts, List<TopProductEntry> weeklyWorstProducts,
            List<DailyCAEntry> dailyCALast30, List<DailyCAEntry> weeklyCA,
            List<DailyCAEntry> monthlyCA, List<DailyCAEntry> yearlyCA,
            List<StoreOverviewEntry> storeOverviews) {
        this(todayCA, yesterdayCA, dayBeforeYesterdayCA, heroTrendPercent,
                totalTransactionsMonth, prevMonthTransactions,
                averageBasketMonth, prevMonthAverageBasket,
                lowStockCount, todaySalesCount,
                weeklyTopProducts, weeklyWorstProducts, dailyCALast30,
                weeklyCA, monthlyCA, yearlyCA, storeOverviews, PeriodTotals.EMPTY);
    }

    /** CA and number of COMPLETED sales over one period, all stores. */
    public record PeriodTotal(long amount, int salesCount) {
        public static final PeriodTotal ZERO = new PeriodTotal(0, 0);
    }

    /**
     * « Chiffre d'affaires total » of the owner dashboard, cut on the WAT calendar: current
     * week (Monday 00:00 → now), current month, current year, and all time.
     */
    public record PeriodTotals(PeriodTotal week, PeriodTotal month, PeriodTotal year, PeriodTotal allTime) {
        public static final PeriodTotals EMPTY =
                new PeriodTotals(PeriodTotal.ZERO, PeriodTotal.ZERO, PeriodTotal.ZERO, PeriodTotal.ZERO);
    }

    public record TopProductEntry(
            String productId,
            String productName,
            int unitsSold,
            long revenue,
            double sharePercent
    ) {}

    public record DailyCAEntry(
            String date,
            long amount
    ) {}

    public record StoreOverviewEntry(
            String storeId,
            String storeName,
            long todayCA,
            long yesterdayCA,
            int employeeCount,
            String statusLevel,
            List<DailyCAEntry> dailyCA,
            List<DailyCAEntry> weeklyCA,
            List<DailyCAEntry> monthlyCA,
            List<DailyCAEntry> yearlyCA
    ) {
        public static String computeStatus(long todayCA, long yesterdayCA) {
            if (yesterdayCA == 0) return "STABLE";
            double ratio = (double) todayCA / yesterdayCA;
            if (ratio >= 0.8) return "STABLE";
            if (ratio >= 0.5) return "ATTENTION";
            return "EN_BAISSE";
        }
    }
}
