package com.keevo.reporting.profitability.domain.service;

import com.keevo.reporting.profitability.domain.model.RankingMetric;
import com.keevo.reporting.profitability.domain.model.StorePerformanceEntry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 1.3 — TDD RED: StoreRankingStrategy tests.
 * Story 7.4.
 */
class StoreRankingStrategyTest {

    private StorePerformanceEntry entry(String name, long revenue, int sales, long avgBasket) {
        return new StorePerformanceEntry(0, UUID.randomUUID(), name, revenue, sales, avgBasket, "Product", 0.0);
    }

    private List<StorePerformanceEntry> stores() {
        return List.of(
                entry("Store_A", 50000, 10, 5000),
                entry("Store_C", 90000, 5,  18000),
                entry("Store_B", 70000, 15, 4667)
        );
    }

    @Test
    void CA_ranks_by_totalRevenue_descending() {
        var strategy = StoreRankingStrategyFactory.of(RankingMetric.CA);
        var ranked = strategy.rank(stores());

        assertThat(ranked.get(0).storeName()).isEqualTo("Store_C");   // 90000
        assertThat(ranked.get(1).storeName()).isEqualTo("Store_B");   // 70000
        assertThat(ranked.get(2).storeName()).isEqualTo("Store_A");   // 50000

        // Rank badge numbers
        assertThat(ranked.get(0).rank()).isEqualTo(1);
        assertThat(ranked.get(1).rank()).isEqualTo(2);
        assertThat(ranked.get(2).rank()).isEqualTo(3);
    }

    @Test
    void SALES_COUNT_ranks_by_salesCount_descending() {
        var strategy = StoreRankingStrategyFactory.of(RankingMetric.SALES_COUNT);
        var ranked = strategy.rank(stores());

        assertThat(ranked.get(0).storeName()).isEqualTo("Store_B");  // 15 sales
        assertThat(ranked.get(1).storeName()).isEqualTo("Store_A");  // 10 sales
        assertThat(ranked.get(2).storeName()).isEqualTo("Store_C");  // 5 sales
    }

    @Test
    void AVG_BASKET_ranks_by_averageBasket_descending() {
        var strategy = StoreRankingStrategyFactory.of(RankingMetric.AVG_BASKET);
        var ranked = strategy.rank(stores());

        assertThat(ranked.get(0).storeName()).isEqualTo("Store_C");   // 18000
        assertThat(ranked.get(1).storeName()).isEqualTo("Store_A");   // 5000
        assertThat(ranked.get(2).storeName()).isEqualTo("Store_B");   // 4667
    }
}
