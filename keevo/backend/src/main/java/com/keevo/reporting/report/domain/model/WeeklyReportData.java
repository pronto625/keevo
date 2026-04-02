package com.keevo.reporting.report.domain.model;

import com.keevo.reporting.report.domain.model.EndOfDayReportData.EmployeeEntry;
import com.keevo.reporting.report.domain.model.EndOfDayReportData.TopProductEntry;

import java.time.LocalDate;
import java.util.List;

/**
 * WeeklyReportData — Value object for an assembled weekly report.
 * Story 7.3 — Rapport Hebdomadaire Automatique.
 *
 * <p>Assembled by WeeklyReportBuilder from a Mon–Sun sales window.
 * Reuses TopProductEntry and EmployeeEntry from EndOfDayReportData to avoid duplication.
 *
 * <p>weekOverWeekDelta = totalRevenue - previousWeekRevenue.
 * avgBasket = 0 when totalSales == 0 (no division by zero).
 */
public record WeeklyReportData(
        String storeName,
        LocalDate weekStart,                            // Monday of the reported week
        LocalDate weekEnd,                              // Sunday of the reported week
        int totalSales,
        int totalRevenue,
        int cashAmount,
        int momoAmount,
        int avgBasket,                                  // 0 if totalSales == 0
        List<TopProductEntry> topProductsByRevenue,     // top 5 by SUM(subtotal)
        List<TopProductEntry> topProductsByQty,         // top 5 by SUM(quantity)
        List<EmployeeEntry> employeeBreakdown,
        int previousWeekRevenue,                        // 0 if no previous WEEKLY report found
        int weekOverWeekDelta,                          // totalRevenue − previousWeekRevenue
        int lowStockCount,
        boolean isAutomatic
) {}
