package com.keevo.reporting.dashboard.domain.model;

import com.keevo.reporting.dashboard.domain.model.DashboardSummary.StoreOverviewEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DashboardSummaryTest — Story 7.1, Task 2.4.
 */
class DashboardSummaryTest {

    @Test
    void dashboardSummary_recordAccessors_work() {
        var summary = new DashboardSummary(
                100L, 200L, 150L, 33.3, 10, 8, 2000L, 1800L, 2, 5,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of()
        );

        assertThat(summary.todayCA()).isEqualTo(100L);
        assertThat(summary.yesterdayCA()).isEqualTo(200L);
        assertThat(summary.heroTrendPercent()).isEqualTo(33.3);
        assertThat(summary.totalTransactionsMonth()).isEqualTo(10);
        assertThat(summary.lowStockCount()).isEqualTo(2);
    }

    @ParameterizedTest
    @CsvSource({
            "80000, 100000, STABLE",    // 80% — >= 0.8
            "79999, 100000, ATTENTION",  // just under 80%
            "60000, 100000, ATTENTION",  // 60% — [0.5, 0.8)
            "49999, 100000, EN_BAISSE",  // just under 50%
            "10000, 100000, EN_BAISSE",  // 10% — < 0.5
            "0,     100000, EN_BAISSE",  // 0% — no sales today
            "50000, 0,      STABLE",     // yesterday=0 → always STABLE
            "0,     0,      STABLE",     // both 0 → STABLE
    })
    void computeStatus_returnsCorrectLevel(long todayCA, long yesterdayCA, String expected) {
        assertThat(StoreOverviewEntry.computeStatus(todayCA, yesterdayCA)).isEqualTo(expected);
    }
}
