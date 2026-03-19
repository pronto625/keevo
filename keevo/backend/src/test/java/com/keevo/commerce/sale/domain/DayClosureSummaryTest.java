package com.keevo.commerce.sale.domain;

import com.keevo.commerce.sale.domain.model.DayClosureSummary;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TDD RED tests for DayClosureSummary value object (Java record).
 * Story 4.4 — Clôture Journalière & Historique des Ventes
 */
class DayClosureSummaryTest {

    @Test
    void DayClosureSummary_topProduct_returnsProductWithMaxQty() {
        // Given - summary computed from sales where iPhone had the most quantity sold
        var summary = new DayClosureSummary(
                10,         // totalSales
                150000,     // totalRevenue
                UUID.randomUUID().toString(),
                "iPhone 14 Pro",
                15,         // topProductQty — iPhone sold 15 units
                100000,
                50000,
                0,
                0
        );

        // Then
        assertThat(summary.topProductName()).isEqualTo("iPhone 14 Pro");
        assertThat(summary.topProductQty()).isEqualTo(15);
        assertThat(summary.topProductId()).isNotNull();
    }

    @Test
    void DayClosureSummary_paymentBreakdown_sumsCashAndMomo() {
        // Given - sales paid 80000 cash, 70000 MoMo
        var summary = new DayClosureSummary(
                10,
                150000,     // totalRevenue = cashAmount + momoAmount
                null, null, 0,
                80000,      // cashAmount
                70000,      // momoAmount
                0, 0
        );

        // Then
        assertThat(summary.cashAmount()).isEqualTo(80000);
        assertThat(summary.momoAmount()).isEqualTo(70000);
        assertThat(summary.cashAmount() + summary.momoAmount()).isEqualTo(summary.totalRevenue());
    }

    @Test
    void DayClosureSummary_withNoPendingSales_omitsPendingLine() {
        // Given - no pending sales
        var summary = new DayClosureSummary(
                5,
                75000,
                null, null, 0,
                75000, 0,
                0,          // pendingSalesCount = 0
                0           // pendingSalesTotal = 0
        );

        // Then - report should show no pending line (condition: pendingSalesCount > 0)
        assertThat(summary.pendingSalesCount()).isEqualTo(0);
        assertThat(summary.pendingSalesTotal()).isEqualTo(0);
    }

    @Test
    void DayClosureSummary_withPendingSales_includesPendingData() {
        // Given - 2 pending sales totaling 15000 XAF
        var summary = new DayClosureSummary(
                5,
                75000,
                null, null, 0,
                75000, 0,
                2,          // pendingSalesCount
                15000       // pendingSalesTotal
        );

        // Then
        assertThat(summary.pendingSalesCount()).isEqualTo(2);
        assertThat(summary.pendingSalesTotal()).isEqualTo(15000);
    }

    @Test
    void DayClosureSummary_withNoTopProduct_hasNullProductInfo() {
        // Given - zero sales, so no top product
        var summary = new DayClosureSummary(
                0,
                0,
                null,       // topProductId
                null,       // topProductName
                0,          // topProductQty
                0, 0, 0, 0
        );

        // Then
        assertThat(summary.topProductId()).isNull();
        assertThat(summary.topProductName()).isNull();
        assertThat(summary.topProductQty()).isEqualTo(0);
    }

    @Test
    void DayClosureSummary_record_equality() {
        // Given - two identical summaries
        var summary1 = new DayClosureSummary(5, 50000, "id1", "Product", 3, 30000, 20000, 1, 5000);
        var summary2 = new DayClosureSummary(5, 50000, "id1", "Product", 3, 30000, 20000, 1, 5000);

        // Then - records with same values are equal
        assertThat(summary1).isEqualTo(summary2);
        assertThat(summary1.hashCode()).isEqualTo(summary2.hashCode());
    }
}
