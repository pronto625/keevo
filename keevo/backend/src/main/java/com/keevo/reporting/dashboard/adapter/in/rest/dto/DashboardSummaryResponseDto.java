package com.keevo.reporting.dashboard.adapter.in.rest.dto;

import com.keevo.reporting.dashboard.domain.model.DashboardSummary;

import java.util.List;

/**
 * DashboardSummaryResponseDto — REST response for GET /api/v1/dashboard/summary.
 * Story 7.1, Task 2.
 */
public record DashboardSummaryResponseDto(
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
        List<TopProductDto> weeklyTopProducts,
        List<TopProductDto> weeklyWorstProducts,
        List<DailyCADto> dailyCALast30,
        List<DailyCADto> weeklyCA,
        List<DailyCADto> monthlyCA,
        List<DailyCADto> yearlyCA,
        List<StoreOverviewDto> storeOverviews
) {

    public record TopProductDto(
            String productId,
            String productName,
            int unitsSold,
            long revenue,
            double sharePercent
    ) {
        public static TopProductDto from(DashboardSummary.TopProductEntry e) {
            return new TopProductDto(e.productId(), e.productName(), e.unitsSold(), e.revenue(), e.sharePercent());
        }
    }

    public record DailyCADto(String date, long amount) {
        public static DailyCADto from(DashboardSummary.DailyCAEntry e) {
            return new DailyCADto(e.date(), e.amount());
        }
    }

    public record StoreOverviewDto(
            String storeId,
            String storeName,
            long todayCA,
            long yesterdayCA,
            int employeeCount,
            String statusLevel,
            List<DailyCADto> dailyCA,
            List<DailyCADto> weeklyCA,
            List<DailyCADto> monthlyCA,
            List<DailyCADto> yearlyCA
    ) {
        public static StoreOverviewDto from(DashboardSummary.StoreOverviewEntry e) {
            return new StoreOverviewDto(
                    e.storeId(), e.storeName(), e.todayCA(), e.yesterdayCA(),
                    e.employeeCount(), e.statusLevel(),
                    e.dailyCA().stream().map(DailyCADto::from).toList(),
                    e.weeklyCA().stream().map(DailyCADto::from).toList(),
                    e.monthlyCA().stream().map(DailyCADto::from).toList(),
                    e.yearlyCA().stream().map(DailyCADto::from).toList()
            );
        }
    }

    public static DashboardSummaryResponseDto from(DashboardSummary s) {
        return new DashboardSummaryResponseDto(
                s.todayCA(),
                s.yesterdayCA(),
                s.dayBeforeYesterdayCA(),
                s.heroTrendPercent(),
                s.totalTransactionsMonth(),
                s.prevMonthTransactions(),
                s.averageBasketMonth(),
                s.prevMonthAverageBasket(),
                s.lowStockCount(),
                s.todaySalesCount(),
                s.weeklyTopProducts().stream().map(TopProductDto::from).toList(),
                s.weeklyWorstProducts().stream().map(TopProductDto::from).toList(),
                s.dailyCALast30().stream().map(DailyCADto::from).toList(),
                s.weeklyCA().stream().map(DailyCADto::from).toList(),
                s.monthlyCA().stream().map(DailyCADto::from).toList(),
                s.yearlyCA().stream().map(DailyCADto::from).toList(),
                s.storeOverviews().stream().map(StoreOverviewDto::from).toList()
        );
    }
}
