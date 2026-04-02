package com.keevo.reporting.report.application.service;

import com.keevo.reporting.report.domain.model.EndOfDayReportData.EmployeeEntry;
import com.keevo.reporting.report.domain.model.EndOfDayReportData.TopProductEntry;
import com.keevo.reporting.report.domain.model.WeeklyReportData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WeeklyReportFormatterTest — TDD RED tests for the weekly WhatsApp formatter.
 * Story 7.3 — Task 3.1.
 *
 * Pure function — no Spring context, no mocks needed.
 */
class WeeklyReportFormatterTest {

    private WeeklyReportFormatter formatter;

    private static final LocalDate MONDAY = LocalDate.of(2026, 3, 23);
    private static final LocalDate SUNDAY = LocalDate.of(2026, 3, 29);

    @BeforeEach
    void setUp() {
        formatter = new WeeklyReportFormatter();
    }

    private WeeklyReportData buildData(int totalSales, int totalRevenue, int cash,
                                       int momo, int avg, int prevRevenue, int delta,
                                       List<TopProductEntry> byRevenue,
                                       List<TopProductEntry> byQty,
                                       List<EmployeeEntry> employees,
                                       int lowStock, boolean isAuto) {
        return new WeeklyReportData(
                "Boutique Alpha", MONDAY, SUNDAY,
                totalSales, totalRevenue, cash, momo, avg,
                byRevenue, byQty, employees,
                prevRevenue, delta, lowStock, isAuto
        );
    }

    private WeeklyReportData standardData() {
        return buildData(
                20, 400_000, 240_000, 160_000, 20_000,
                350_000, 50_000,
                List.of(
                        new TopProductEntry("Savon Mane", 10, 100_000),
                        new TopProductEntry("Huile 1L", 8, 80_000),
                        new TopProductEntry("Sucre 1kg", 15, 60_000)
                ),
                List.of(
                        new TopProductEntry("Sucre 1kg", 15, 60_000),
                        new TopProductEntry("Savon Mane", 10, 100_000)
                ),
                List.of(new EmployeeEntry("Alice Ngo", 12, 240_000)),
                2, false
        );
    }

    @Test
    void formatter_producesExpectedWeeklyFormat() {
        String result = formatter.format(standardData());

        assertThat(result).contains("📅 Rapport Hebdomadaire");
        assertThat(result).contains("Boutique Alpha");
        assertThat(result).contains("CA Semaine");
        assertThat(result).contains("Top 5 produits (revenus)");
        assertThat(result).contains("Savon Mane");
    }

    @Test
    void formatter_containsWeekDateRange() {
        String result = formatter.format(standardData());
        // Should contain "Semaine du" and both dates
        assertThat(result).contains("Semaine du");
        assertThat(result).contains("29");  // Sunday date
    }

    @Test
    void formatter_positiveWoWDelta_showsUpArrow() {
        WeeklyReportData data = buildData(
                10, 500_000, 300_000, 200_000, 50_000,
                400_000, 100_000,  // +100000 delta
                List.of(new TopProductEntry("P1", 5, 500_000)),
                List.of(new TopProductEntry("P1", 5, 500_000)),
                List.of(),
                0, false
        );
        String result = formatter.format(data);
        assertThat(result).contains("↑");
        assertThat(result).contains("+");
    }

    @Test
    void formatter_negativeWoWDelta_showsDownArrow() {
        WeeklyReportData data = buildData(
                5, 200_000, 120_000, 80_000, 40_000,
                350_000, -150_000,  // negative delta
                List.of(new TopProductEntry("P1", 5, 200_000)),
                List.of(new TopProductEntry("P1", 5, 200_000)),
                List.of(),
                0, false
        );
        String result = formatter.format(data);
        assertThat(result).contains("↓");
    }

    @Test
    void formatter_zeroDelta_showsArrowRight() {
        WeeklyReportData data = buildData(
                5, 200_000, 120_000, 80_000, 40_000,
                200_000, 0,  // zero delta
                List.of(new TopProductEntry("P1", 5, 200_000)),
                List.of(new TopProductEntry("P1", 5, 200_000)),
                List.of(),
                0, false
        );
        String result = formatter.format(data);
        assertThat(result).contains("→");
    }

    @Test
    void formatter_noPreviousWeek_showsInsufficientData() {
        WeeklyReportData data = buildData(
                5, 200_000, 120_000, 80_000, 40_000,
                0, 200_000,  // no previous week (previousWeekRevenue==0)
                List.of(new TopProductEntry("P1", 5, 200_000)),
                List.of(new TopProductEntry("P1", 5, 200_000)),
                List.of(),
                0, false
        );
        String result = formatter.format(data);
        assertThat(result).contains("données insuffisantes");
    }

    @Test
    void formatter_zeroSales_showsAucuneVente() {
        WeeklyReportData data = buildData(
                0, 0, 0, 0, 0,
                0, 0,
                List.of(), List.of(), List.of(),
                0, false
        );
        String result = formatter.format(data);
        assertThat(result).contains("Aucune vente enregistrée cette semaine");
        assertThat(result).doesNotContain("CA Semaine");
    }

    @Test
    void formatter_topProductsTruncatedToActualCount() {
        // Only 3 products (less than 5)
        List<TopProductEntry> threeProducts = List.of(
                new TopProductEntry("P1", 5, 100_000),
                new TopProductEntry("P2", 3, 60_000),
                new TopProductEntry("P3", 2, 40_000)
        );
        WeeklyReportData data = buildData(
                10, 200_000, 120_000, 80_000, 20_000,
                150_000, 50_000,
                threeProducts, threeProducts,
                List.of(),
                0, false
        );
        String result = formatter.format(data);
        // Must show P1, P2, P3 — should not contain P4 or P5
        assertThat(result).contains("P1");
        assertThat(result).contains("P2");
        assertThat(result).contains("P3");
        assertThat(result).doesNotContain("P4");
    }

    @Test
    void formatter_isAutomatic_appendsAutoNote() {
        WeeklyReportData data = buildData(
                5, 100_000, 60_000, 40_000, 20_000,
                0, 100_000,
                List.of(new TopProductEntry("P1", 5, 100_000)),
                List.of(new TopProductEntry("P1", 5, 100_000)),
                List.of(),
                0, true  // isAutomatic=true
        );
        String result = formatter.format(data);
        assertThat(result).contains("⏰");
        assertThat(result).contains("auto-généré");
    }

    @Test
    void formatter_zeroLowStock_showsNoAlert() {
        WeeklyReportData data = buildData(
                5, 100_000, 60_000, 40_000, 20_000,
                80_000, 20_000,
                List.of(new TopProductEntry("P1", 5, 100_000)),
                List.of(new TopProductEntry("P1", 5, 100_000)),
                List.of(),
                0, false  // lowStockCount=0
        );
        String result = formatter.format(data);
        assertThat(result).contains("Aucune alerte");
    }

    @Test
    void formatter_withLowStock_showsCount() {
        WeeklyReportData data = buildData(
                5, 100_000, 60_000, 40_000, 20_000,
                80_000, 20_000,
                List.of(new TopProductEntry("P1", 5, 100_000)),
                List.of(new TopProductEntry("P1", 5, 100_000)),
                List.of(),
                4, false  // lowStockCount=4
        );
        String result = formatter.format(data);
        assertThat(result).contains("4");
        assertThat(result).contains("rupture");
    }
}
