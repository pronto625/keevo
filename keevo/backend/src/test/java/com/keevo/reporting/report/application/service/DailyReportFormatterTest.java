package com.keevo.reporting.report.application.service;

import com.keevo.reporting.report.domain.model.EndOfDayReportData;
import com.keevo.reporting.report.domain.model.EndOfDayReportData.EmployeeEntry;
import com.keevo.reporting.report.domain.model.EndOfDayReportData.TopProductEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DailyReportFormatterTest — TDD GREEN tests for WhatsApp text formatting.
 * Story 7.2 — Task 1.3.
 *
 * <p>Pure unit tests — no mocks needed.
 */
class DailyReportFormatterTest {

    private DailyReportFormatter formatter;

    @BeforeEach
    void setUp() {
        formatter = new DailyReportFormatter();
    }

    private EndOfDayReportData sampleData(int sales, int revenue) {
        return new EndOfDayReportData(
                "Boutique Kinshasa",
                LocalDate.of(2026, 5, 15),
                LocalTime.of(20, 30),
                false,
                false,
                sales,
                revenue,
                100000,
                50000,
                sales > 0 ? revenue / sales : 0,
                List.of(new TopProductEntry("iPhone 14", 3, 90000),
                         new TopProductEntry("Câble USB", 5, 10000)),
                List.of(new EmployeeEntry("Alice", 8, 120000)),
                2,
                1,
                5000
        );
    }

    @Test
    void format_shouldContainStoreName() {
        String result = formatter.format(sampleData(5, 150000));
        assertThat(result).contains("Boutique Kinshasa");
    }

    @Test
    void format_shouldContainDate() {
        String result = formatter.format(sampleData(5, 150000));
        assertThat(result).contains("15 mai 2026");
    }

    @Test
    void format_shouldContainClosureTime() {
        String result = formatter.format(sampleData(5, 150000));
        assertThat(result).contains("20h30");
    }

    @Test
    void format_shouldContainClosureTypeManuel() {
        String result = formatter.format(sampleData(5, 150000));
        assertThat(result).contains("Manuel");
    }

    @Test
    void format_shouldContainTotalRevenue() {
        String result = formatter.format(sampleData(5, 150000));
        assertThat(result).contains("FCFA");
    }

    @Test
    void format_shouldContainTopProductEntry() {
        String result = formatter.format(sampleData(5, 150000));
        assertThat(result).contains("iPhone 14");
    }

    @Test
    void format_shouldContainEmployeeName() {
        String result = formatter.format(sampleData(5, 150000));
        assertThat(result).contains("Alice");
    }

    @Test
    void format_whenNoSales_shouldReturnAucuneVenteMessage() {
        EndOfDayReportData empty = new EndOfDayReportData(
                "Boutique Test", LocalDate.now(), LocalTime.NOON, true,
                false,
                0, 0, 0, 0, 0,
                List.of(), List.of(), 0, 0, 0
        );
        String result = formatter.format(empty);
        assertThat(result).contains("Aucune vente");
    }

    @Test
    void format_shouldContainLowStockAlert() {
        String result = formatter.format(sampleData(5, 150000));
        assertThat(result).contains("Alertes stock");
    }

    @Test
    void format_shouldContainPendingSalesIfAny() {
        String result = formatter.format(sampleData(5, 150000));
        assertThat(result).contains("attente");
    }

    @Test
    void format_automaticClosure_shouldContainAutoLabel() {
        EndOfDayReportData autoData = new EndOfDayReportData(
                "Boutique Auto", LocalDate.now(), LocalTime.MIDNIGHT, true,
                false,
                2, 30000, 20000, 10000, 15000,
                List.of(), List.of(), 0, 0, 0
        );
        String result = formatter.format(autoData);
        assertThat(result).contains("Auto");
    }
}
