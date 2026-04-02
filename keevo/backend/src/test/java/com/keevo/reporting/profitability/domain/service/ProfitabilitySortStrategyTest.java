package com.keevo.reporting.profitability.domain.service;

import com.keevo.reporting.profitability.domain.model.MarginLevel;
import com.keevo.reporting.profitability.domain.model.ProductProfitabilityEntry;
import com.keevo.reporting.profitability.domain.model.SortOption;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 1.2 — TDD RED: ProfitabilitySortStrategy and Factory.
 * Story 7.4.
 */
class ProfitabilitySortStrategyTest {

    private ProductProfitabilityEntry entry(String name, long rev, long cost, int units) {
        long grossMargin = rev - cost;
        double pct = cost == 0 ? 0.0 : ((double) grossMargin / cost) * 100.0;
        boolean isLoss = grossMargin < 0;
        return new ProductProfitabilityEntry(
                UUID.randomUUID(), name, null, units, rev, cost, grossMargin, pct, isLoss, null
        );
    }

    private List<ProductProfitabilityEntry> testList() {
        return List.of(
                entry("B_low", 10500, 10000, 3),    // margin ~5%
                entry("A_profit", 12000, 10000, 1), // margin 20%
                entry("C_loss", 5000, 10000, 5),    // margin -50% (LOSS)
                entry("D_mod", 11500, 10000, 2)     // margin 15%
        );
    }

    @Test
    void MARGIN_PCT_DESC_sorts_descending_byMarginPercent() {
        var strategy = ProfitabilitySortStrategyFactory.of(SortOption.MARGIN_PCT_DESC);
        var sorted = strategy.sort(testList());

        // A_profit (20%) → D_mod (15%) → B_low (5%) → C_loss (-50%)
        assertThat(sorted.get(0).productName()).isEqualTo("A_profit");
        assertThat(sorted.get(1).productName()).isEqualTo("D_mod");
        assertThat(sorted.get(2).productName()).isEqualTo("B_low");
        assertThat(sorted.get(3).productName()).isEqualTo("C_loss");
    }

    @Test
    void MARGIN_PCT_DESC_losses_appear_last() {
        var strategy = ProfitabilitySortStrategyFactory.of(SortOption.MARGIN_PCT_DESC);
        var sorted = strategy.sort(testList());
        assertThat(sorted.get(sorted.size() - 1).isLoss()).isTrue();
    }

    @Test
    void MARGIN_XAF_DESC_sorts_descending_byGrossMarginXaf() {
        var strategy = ProfitabilitySortStrategyFactory.of(SortOption.MARGIN_XAF_DESC);
        var sorted = strategy.sort(testList());

        // A_profit grossMargin=2000, D_mod=1500, B_low=500, C_loss=-5000
        assertThat(sorted.get(0).productName()).isEqualTo("A_profit");
        assertThat(sorted.get(1).productName()).isEqualTo("D_mod");
        assertThat(sorted.get(2).productName()).isEqualTo("B_low");
        assertThat(sorted.get(3).productName()).isEqualTo("C_loss");
    }

    @Test
    void CA_DESC_sorts_descending_byTotalRevenue() {
        var strategy = ProfitabilitySortStrategyFactory.of(SortOption.CA_DESC);
        var sorted = strategy.sort(testList());

        // A_profit=12000, D_mod=11500, B_low=10500, C_loss=5000
        assertThat(sorted.get(0).productName()).isEqualTo("A_profit");
        assertThat(sorted.get(1).productName()).isEqualTo("D_mod");
        assertThat(sorted.get(2).productName()).isEqualTo("B_low");
        assertThat(sorted.get(3).productName()).isEqualTo("C_loss");
    }

    @Test
    void UNITS_DESC_sorts_descending_byUnitsSold() {
        var strategy = ProfitabilitySortStrategyFactory.of(SortOption.UNITS_DESC);
        var sorted = strategy.sort(testList());

        // C_loss=5, B_low=3, D_mod=2, A_profit=1
        assertThat(sorted.get(0).productName()).isEqualTo("C_loss");
        assertThat(sorted.get(1).productName()).isEqualTo("B_low");
        assertThat(sorted.get(2).productName()).isEqualTo("D_mod");
        assertThat(sorted.get(3).productName()).isEqualTo("A_profit");
    }

    @Test
    void factory_MARGIN_PCT_DESC_string_returnsCorrectStrategy() {
        var strategy = ProfitabilitySortStrategyFactory.of(SortOption.fromString("MARGIN_PCT_DESC"));
        assertThat(strategy).isInstanceOf(ByMarginPctDescStrategy.class);
    }

    @Test
    void factory_unknown_string_returnsDefaultMarginPctDescStrategy() {
        var opt = SortOption.fromString("UNKNOWN_SORT");
        assertThat(opt).isEqualTo(SortOption.MARGIN_PCT_DESC);
        var strategy = ProfitabilitySortStrategyFactory.of(opt);
        assertThat(strategy).isInstanceOf(ByMarginPctDescStrategy.class);
    }
}
