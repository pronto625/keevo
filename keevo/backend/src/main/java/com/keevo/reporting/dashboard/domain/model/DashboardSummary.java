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
        List<StoreOverviewEntry> storeOverviews
) {

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
