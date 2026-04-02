package com.keevo.reporting.report.domain.model;

import com.keevo.reporting.report.domain.model.EndOfDayReportData.EmployeeEntry;
import com.keevo.reporting.report.domain.model.EndOfDayReportData.TopProductEntry;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WeeklyReportDataTest — TDD RED tests for the WeeklyReportData value object.
 * Story 7.3 — Task 1.1.
 */
class WeeklyReportDataTest {

    private static final LocalDate MONDAY = LocalDate.of(2026, 3, 23);
    private static final LocalDate SUNDAY = LocalDate.of(2026, 3, 29);

    private WeeklyReportData sampleData() {
        return new WeeklyReportData(
                "Boutique Alpha",
                MONDAY,
                SUNDAY,
                /* totalSales */ 42,
                /* totalRevenue */ 500_000,
                /* cashAmount */ 300_000,
                /* momoAmount */ 200_000,
                /* avgBasket */ 11_904,
                List.of(new TopProductEntry("Savon", 10, 50_000)),
                List.of(new TopProductEntry("Sucre", 20, 40_000)),
                List.of(new EmployeeEntry("Alice Ngo", 20, 250_000)),
                /* previousWeekRevenue */ 450_000,
                /* weekOverWeekDelta */ 50_000,
                /* lowStockCount */ 3,
                /* isAutomatic */ true
        );
    }

    @Test
    void weeklyReportData_create_setsAllFields() {
        WeeklyReportData data = sampleData();

        assertThat(data.storeName()).isEqualTo("Boutique Alpha");
        assertThat(data.weekStart()).isEqualTo(MONDAY);
        assertThat(data.weekEnd()).isEqualTo(SUNDAY);
        assertThat(data.totalSales()).isEqualTo(42);
        assertThat(data.totalRevenue()).isEqualTo(500_000);
        assertThat(data.cashAmount()).isEqualTo(300_000);
        assertThat(data.momoAmount()).isEqualTo(200_000);
        assertThat(data.avgBasket()).isEqualTo(11_904);
        assertThat(data.lowStockCount()).isEqualTo(3);
        assertThat(data.isAutomatic()).isTrue();
    }

    @Test
    void weeklyReportData_zeroSales_avgBasketIsZero() {
        WeeklyReportData data = new WeeklyReportData(
                "Test", MONDAY, SUNDAY,
                0, 0, 0, 0, 0,
                List.of(), List.of(), List.of(),
                0, 0, 0, false
        );
        assertThat(data.totalSales()).isZero();
        assertThat(data.avgBasket()).isZero();
    }

    @Test
    void weeklyReportData_positiveWoWDelta_growthDetected() {
        WeeklyReportData data = sampleData();
        assertThat(data.weekOverWeekDelta()).isPositive();
        assertThat(data.weekOverWeekDelta()).isEqualTo(50_000);
    }

    @Test
    void weeklyReportData_negativeWoWDelta_declineDetected() {
        WeeklyReportData data = new WeeklyReportData(
                "Test", MONDAY, SUNDAY,
                10, 300_000, 200_000, 100_000, 30_000,
                List.of(), List.of(), List.of(),
                /* previousWeekRevenue */ 400_000,
                /* weekOverWeekDelta */ -100_000,
                0, true
        );
        assertThat(data.weekOverWeekDelta()).isNegative();
        assertThat(data.weekOverWeekDelta()).isEqualTo(-100_000);
    }

    @Test
    void weeklyReportData_noPreviousWeek_deltaIsZero() {
        WeeklyReportData data = new WeeklyReportData(
                "Test", MONDAY, SUNDAY,
                5, 100_000, 60_000, 40_000, 20_000,
                List.of(), List.of(), List.of(),
                0, 0, 0, false
        );
        assertThat(data.previousWeekRevenue()).isZero();
        assertThat(data.weekOverWeekDelta()).isZero();
    }

    @Test
    void weeklyReportData_top5Products_limitedToFive() {
        List<TopProductEntry> topByRevenue = List.of(
                new TopProductEntry("P1", 5, 50_000),
                new TopProductEntry("P2", 3, 30_000),
                new TopProductEntry("P3", 2, 20_000),
                new TopProductEntry("P4", 1, 10_000),
                new TopProductEntry("P5", 1, 5_000)
        );
        WeeklyReportData data = new WeeklyReportData(
                "Test", MONDAY, SUNDAY,
                12, 115_000, 70_000, 45_000, 9_583,
                topByRevenue, topByRevenue, List.of(),
                0, 0, 2, false
        );
        assertThat(data.topProductsByRevenue()).hasSize(5);
        assertThat(data.topProductsByQty()).hasSize(5);
    }
}
