package com.keevo.reporting.report.domain.model;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * EndOfDayReportData — Value object holding all raw data needed to format a report.
 * Story 7.2 — Rapport End-of-Day
 *
 * <p>Assembled by EndOfDayReportBuilder from multiple SQL queries.
 */
public record EndOfDayReportData(
        String storeName,
        LocalDate reportDate,
        LocalTime closeTime,
        boolean isAutomatic,
        int totalSales,
        int totalRevenue,
        int cashAmount,
        int momoAmount,
        int avgBasket,
        List<TopProductEntry> topProducts,
        List<EmployeeEntry> employeeBreakdown,
        int lowStockCount,
        int pendingSalesCount,
        int pendingSalesTotal
) {
    public record TopProductEntry(String name, int qty, int revenue) {}
    public record EmployeeEntry(String name, int salesCount, int revenue) {}
}
