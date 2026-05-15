package com.keevo.reporting.report.application.service;

import com.keevo.reporting.report.domain.model.EndOfDayReport;
import com.keevo.reporting.report.domain.model.ReportType;
import com.keevo.reporting.report.domain.model.WeeklyReportData;
import com.keevo.reporting.report.domain.port.out.EndOfDayReportRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * WeeklyReportBuilderTest — TDD RED tests for weekly data assembly from SQL.
 * Story 7.3 — Task 2.1.
 *
 * <p>EntityManager.createNativeQuery() mocked per architecture rule.
 * Call order for getSingleResult():
 * 1. countCompletedSales
 * 2. sumRevenue
 * 3. sumByPaymentMode(CASH)
 * 4. sumByPaymentMode(MOBILE_MONEY)
 * 5. countLowStock
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WeeklyReportBuilderTest {

    @Mock private EntityManager entityManager;
    @Mock private EndOfDayReportRepository reportRepository;
    @Mock private Query query;

    private WeeklyReportBuilder builder;
    private UUID storeId;
    private Instant weekStart;
    private Instant weekEnd;
    private LocalDate weekEndDate;

    @BeforeEach
    void setUp() {
        builder = new WeeklyReportBuilder(entityManager, reportRepository);
        storeId = UUID.randomUUID();
        weekStart = Instant.parse("2026-03-23T00:00:00Z"); // Monday 00:00 UTC
        weekEnd   = Instant.parse("2026-03-29T22:59:59Z"); // Sunday 23:59 WAT = 22:59 UTC
        weekEndDate = LocalDate.of(2026, 3, 29);

        lenient().when(entityManager.createNativeQuery(anyString())).thenReturn(query);
        lenient().when(query.setParameter(anyString(), any())).thenReturn(query);
        lenient().when(query.getResultList()).thenReturn(List.of());
        lenient().when(query.getSingleResult()).thenReturn(0);
    }

    /**
     * Stubs call-order for getSingleResult():
     * countSales, sumRevenue, cash, momo, lowStock.
     */
    private void stubSingleResults(int sales, int revenue, int cash, int momo, int lowStock) {
        when(query.getSingleResult()).thenReturn(sales, revenue, cash, momo, lowStock);
    }

    @Test
    void build_withSalesInWindow_computesTotalCAAndCount() {
        stubSingleResults(15, 375_000, 200_000, 175_000, 3);
        when(reportRepository.findByDateAndTenant(any(), anyString(), eq(ReportType.WEEKLY)))
                .thenReturn(List.of());

        WeeklyReportData data = builder.build(
                storeId, weekStart, weekEnd, "Store A", weekEndDate, "kv_test01", false);

        assertThat(data.totalSales()).isEqualTo(15);
        assertThat(data.totalRevenue()).isEqualTo(375_000);
    }

    @Test
    void build_withZeroSales_returnsEmptyWeeklyData() {
        stubSingleResults(0, 0, 0, 0, 0);
        when(reportRepository.findByDateAndTenant(any(), anyString(), eq(ReportType.WEEKLY)))
                .thenReturn(List.of());

        WeeklyReportData data = builder.build(
                storeId, weekStart, weekEnd, "Store Empty", weekEndDate, "kv_test01", true);

        assertThat(data.totalSales()).isZero();
        assertThat(data.totalRevenue()).isZero();
        assertThat(data.avgBasket()).isZero();
    }

    @Test
    void build_splitsCashAndMomo() {
        stubSingleResults(10, 300_000, 180_000, 120_000, 1);
        when(reportRepository.findByDateAndTenant(any(), anyString(), eq(ReportType.WEEKLY)))
                .thenReturn(List.of());

        WeeklyReportData data = builder.build(
                storeId, weekStart, weekEnd, "Store B", weekEndDate, "kv_test01", false);

        assertThat(data.cashAmount()).isEqualTo(180_000);
        assertThat(data.momoAmount()).isEqualTo(120_000);
    }

    @Test
    void build_computesTop5ByRevenue_limitedToFive() {
        stubSingleResults(20, 500_000, 300_000, 200_000, 2);
        List<Object[]> top5Rows = List.of(
                new Object[]{"P1", 5, 100_000},
                new Object[]{"P2", 8, 90_000},
                new Object[]{"P3", 3, 80_000},
                new Object[]{"P4", 4, 70_000},
                new Object[]{"P5", 6, 60_000}
        );
        // First getResultList() call = top5ByRevenue, second = top5ByQty, third = employeeBreakdown
        when(query.getResultList()).thenReturn(top5Rows, top5Rows, List.of());
        when(reportRepository.findByDateAndTenant(any(), anyString(), eq(ReportType.WEEKLY)))
                .thenReturn(List.of());

        WeeklyReportData data = builder.build(
                storeId, weekStart, weekEnd, "Store C", weekEndDate, "kv_test01", false);

        assertThat(data.topProductsByRevenue()).hasSize(5);
        assertThat(data.topProductsByRevenue().get(0).name()).isEqualTo("P1");
        assertThat(data.topProductsByRevenue().get(0).revenue()).isEqualTo(100_000);
    }

    @Test
    void build_computesTop5ByQty_limitedToFive() {
        stubSingleResults(20, 500_000, 300_000, 200_000, 2);
        List<Object[]> top5Rows = List.of(
                new Object[]{"P1", 50, 100_000},
                new Object[]{"P2", 40, 90_000},
                new Object[]{"P3", 30, 80_000},
                new Object[]{"P4", 20, 70_000},
                new Object[]{"P5", 10, 60_000}
        );
        when(query.getResultList()).thenReturn(top5Rows, top5Rows, List.of());
        when(reportRepository.findByDateAndTenant(any(), anyString(), eq(ReportType.WEEKLY)))
                .thenReturn(List.of());

        WeeklyReportData data = builder.build(
                storeId, weekStart, weekEnd, "Store D", weekEndDate, "kv_test01", false);

        assertThat(data.topProductsByQty()).hasSize(5);
        assertThat(data.topProductsByQty().get(0).qty()).isEqualTo(50);
    }

    @Test
    void build_computesEmployeeBreakdown() {
        stubSingleResults(10, 200_000, 120_000, 80_000, 0);
        List<Object[]> employeeRows = List.of(
                new Object[]{"Alice", 6, 120_000},
                new Object[]{"Bob", 4, 80_000}
        );
        when(query.getResultList()).thenReturn(List.of(), List.of(), employeeRows);
        when(reportRepository.findByDateAndTenant(any(), anyString(), eq(ReportType.WEEKLY)))
                .thenReturn(List.of());

        WeeklyReportData data = builder.build(
                storeId, weekStart, weekEnd, "Store E", weekEndDate, "kv_test01", false);

        assertThat(data.employeeBreakdown()).hasSize(2);
        assertThat(data.employeeBreakdown().get(0).name()).isEqualTo("Alice");
        assertThat(data.employeeBreakdown().get(0).salesCount()).isEqualTo(6);
    }

    @Test
    void build_computesWeekOverWeekDelta_withPreviousData() {
        stubSingleResults(10, 120_000, 80_000, 40_000, 1);
        when(query.getResultList()).thenReturn(List.of(), List.of(), List.of());

        // Previous week: one WEEKLY report found
        EndOfDayReport prevReport = EndOfDayReport.createNew(
                "kv_test01", storeId, "Store A", null,
                null,  // actorName
                ReportType.WEEKLY, weekEndDate.minusWeeks(1),
                "previous week content", 100_000, 8, true);
        when(reportRepository.findByDateAndTenant(eq(weekEndDate.minusWeeks(1)), anyString(), eq(ReportType.WEEKLY)))
                .thenReturn(List.of(prevReport));

        WeeklyReportData data = builder.build(
                storeId, weekStart, weekEnd, "Store A", weekEndDate, "kv_test01", true);

        assertThat(data.previousWeekRevenue()).isEqualTo(100_000);
        assertThat(data.weekOverWeekDelta()).isEqualTo(20_000); // 120000 - 100000
    }

    @Test
    void build_computesWeekOverWeekDelta_noPreviousData() {
        stubSingleResults(5, 80_000, 50_000, 30_000, 0);
        when(query.getResultList()).thenReturn(List.of(), List.of(), List.of());
        when(reportRepository.findByDateAndTenant(any(), anyString(), eq(ReportType.WEEKLY)))
                .thenReturn(List.of());

        WeeklyReportData data = builder.build(
                storeId, weekStart, weekEnd, "Store F", weekEndDate, "kv_test01", false);

        assertThat(data.previousWeekRevenue()).isZero();
        assertThat(data.weekOverWeekDelta()).isEqualTo(80_000); // 80000 - 0
    }

    @Test
    void build_computesLowStockCount() {
        stubSingleResults(3, 60_000, 40_000, 20_000, 5);
        when(query.getResultList()).thenReturn(List.of(), List.of(), List.of());
        when(reportRepository.findByDateAndTenant(any(), anyString(), eq(ReportType.WEEKLY)))
                .thenReturn(List.of());

        WeeklyReportData data = builder.build(
                storeId, weekStart, weekEnd, "Store G", weekEndDate, "kv_test01", false);

        assertThat(data.lowStockCount()).isEqualTo(5);
    }
}
