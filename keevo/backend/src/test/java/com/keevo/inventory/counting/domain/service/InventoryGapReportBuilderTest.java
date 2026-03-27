package com.keevo.inventory.counting.domain.service;

import com.keevo.inventory.counting.domain.model.*;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class InventoryGapReportBuilderTest {

    private static final UUID SESSION_ID = UUID.randomUUID();
    private static final UUID STORE_ID = UUID.randomUUID();
    private static final UUID ACTOR_ID = UUID.randomUUID();

    @Test
    void build_emptyReport_shouldReturnZeroSummary() {
        InventoryGapReport report = builder().build();

        assertThat(report.getSummary().totalCounted()).isZero();
        assertThat(report.getSummary().totalConcordant()).isZero();
        assertThat(report.getSummary().totalSurplus()).isZero();
        assertThat(report.getSummary().totalShortage()).isZero();
        assertThat(report.getConcordantRows()).isEmpty();
        assertThat(report.getSurplusRows()).isEmpty();
        assertThat(report.getShortageRows()).isEmpty();
    }

    @Test
    void build_allConcordant_shouldHaveZeroGaps() {
        InventoryGapReport report = builder()
                .addRow(count("Jeans", 10, 10), 5000)
                .addRow(count("T-Shirt", 5, 5), 3000)
                .build();

        assertThat(report.getSummary().totalCounted()).isEqualTo(2);
        assertThat(report.getSummary().totalConcordant()).isEqualTo(2);
        assertThat(report.getSummary().totalSurplus()).isZero();
        assertThat(report.getSummary().totalShortage()).isZero();
        assertThat(report.getSummary().totalSurplusValueXaf()).isZero();
        assertThat(report.getSummary().totalShortageValueXaf()).isZero();
        assertThat(report.getConcordantRows()).hasSize(2);
    }

    @Test
    void build_mixedGaps_shouldSeparateSections() {
        InventoryGapReport report = builder()
                .addRow(count("Concordant", 10, 10), 5000)
                .addRow(count("Surplus", 5, 8), 3000)      // +3
                .addRow(count("Shortage", 10, 5), 5000)     // -5
                .build();

        assertThat(report.getSummary().totalCounted()).isEqualTo(3);
        assertThat(report.getSummary().totalConcordant()).isEqualTo(1);
        assertThat(report.getSummary().totalSurplus()).isEqualTo(1);
        assertThat(report.getSummary().totalShortage()).isEqualTo(1);
        assertThat(report.getConcordantRows()).hasSize(1);
        assertThat(report.getSurplusRows()).hasSize(1);
        assertThat(report.getShortageRows()).hasSize(1);
    }

    @Test
    void build_shortageSortedByValueDesc() {
        InventoryGapReport report = builder()
                .addRow(count("Cheap", 10, 5), 1000)    // -5 * 1000 = 5000
                .addRow(count("Expensive", 10, 8), 10000) // -2 * 10000 = 20000
                .addRow(count("Medium", 10, 7), 3000)   // -3 * 3000 = 9000
                .build();

        assertThat(report.getShortageRows()).hasSize(3);
        assertThat(report.getShortageRows().get(0).productName()).isEqualTo("Expensive");
        assertThat(report.getShortageRows().get(1).productName()).isEqualTo("Medium");
        assertThat(report.getShortageRows().get(2).productName()).isEqualTo("Cheap");
    }

    @Test
    void build_surplusXafCalculatedCorrectly() {
        InventoryGapReport report = builder()
                .addRow(count("Surplus1", 5, 8), 3000)   // +3 * 3000 = 9000
                .addRow(count("Surplus2", 2, 7), 2000)   // +5 * 2000 = 10000
                .build();

        assertThat(report.getSummary().totalSurplusValueXaf()).isEqualTo(19000L);
        assertThat(report.getSurplusRows().get(0).gapValueXaf()).isEqualTo(10000L);
        assertThat(report.getSurplusRows().get(1).gapValueXaf()).isEqualTo(9000L);
    }

    @Test
    void build_summaryTotalsCorrect() {
        InventoryGapReport report = builder()
                .addRow(count("A", 10, 10), 5000)        // concordant
                .addRow(count("B", 10, 10), 3000)        // concordant
                .addRow(count("C", 5, 8), 3000)          // surplus +3 * 3000 = 9000
                .addRow(count("D", 10, 5), 5000)         // shortage -5 * 5000 = 25000
                .addRow(count("E", 8, 6), 4000)          // shortage -2 * 4000 = 8000
                .build();

        assertThat(report.getSummary().totalCounted()).isEqualTo(5);
        assertThat(report.getSummary().totalConcordant()).isEqualTo(2);
        assertThat(report.getSummary().totalSurplus()).isEqualTo(1);
        assertThat(report.getSummary().totalShortage()).isEqualTo(2);
        assertThat(report.getSummary().totalSurplusValueXaf()).isEqualTo(9000L);
        assertThat(report.getSummary().totalShortageValueXaf()).isEqualTo(33000L);
    }

    // ── Helpers ──

    private InventoryGapReportBuilder builder() {
        return new InventoryGapReportBuilder()
                .sessionId(SESSION_ID)
                .storeId(STORE_ID)
                .storeName("Boutique Centrale")
                .scope(InventoryScope.FULL)
                .generatedBy(ACTOR_ID);
    }

    private InventoryCount count(String name, int theoretical, int physical) {
        return InventoryCount.create(
                SESSION_ID, UUID.randomUUID(), null,
                name, null,
                theoretical, physical, ACTOR_ID);
    }
}
