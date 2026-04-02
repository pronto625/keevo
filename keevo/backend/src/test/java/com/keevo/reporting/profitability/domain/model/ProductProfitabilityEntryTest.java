package com.keevo.reporting.profitability.domain.model;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 1.1 — TDD RED: domain model for ProductProfitabilityEntry.
 * Story 7.4.
 */
class ProductProfitabilityEntryTest {

    private ProductProfitabilityEntry entry(long totalRevenue, long totalCost) {
        long grossMarginXaf = totalRevenue - totalCost;
        double marginPercent = totalCost == 0 ? 0.0
                : ((double) grossMarginXaf / totalCost) * 100.0;
        boolean isLoss = grossMarginXaf < 0;
        return new ProductProfitabilityEntry(
                UUID.randomUUID(), "Prod", null, 1,
                totalRevenue, totalCost, grossMarginXaf, marginPercent, isLoss, null
        );
    }

    @Test
    void entry_withPositiveMargin_isNotLoss() {
        var e = entry(10000, 7000);
        assertThat(e.isLoss()).isFalse();
    }

    @Test
    void entry_withNegativeMargin_isLoss() {
        var e = entry(5000, 7000);
        assertThat(e.isLoss()).isTrue();
    }

    @Test
    void entry_marginPercent_computedFromTotalCostBase() {
        // grossMarginXaf = 10500 – 4500 = 5500 … wait full: rev=15000, cost=4500
        // marginPercent = (15000-4500)/4500 * 100 = 10500/4500 * 100 = 233.33%
        long rev = 15000, cost = 4500;
        var e = entry(rev, cost);
        assertThat(e.marginPercent()).isCloseTo(233.33, org.assertj.core.data.Offset.offset(0.1));
    }

    @Test
    void entry_zeroUnitsSold_marginPercentIsZero() {
        // Units=0, but we use totalCost/totalRevenue directly
        var e = new ProductProfitabilityEntry(
                UUID.randomUUID(), "Prod", null, 0,
                0, 0, 0, 0.0, false, null
        );
        assertThat(e.marginPercent()).isEqualTo(0.0);
    }

    @Test
    void entry_zeroTotalCost_marginPercentIsZero() {
        var e = entry(5000, 0);
        assertThat(e.marginPercent()).isEqualTo(0.0);
    }

    @Test
    void entry_marginLevel_low_whenPercentLessThan10() {
        var e = entry(1050, 1000); // margin = 5%
        assertThat(e.marginLevel()).isEqualTo(MarginLevel.LOW);
    }

    @Test
    void entry_marginLevel_loss_whenNegative() {
        var e = entry(500, 1000); // margin = -50%
        assertThat(e.isLoss()).isTrue();
        assertThat(e.marginLevel()).isEqualTo(MarginLevel.LOSS);
    }

    @Test
    void entry_marginLevel_moderate_whenPercent10to19() {
        var e = entry(1150, 1000); // margin = 15%
        assertThat(e.marginLevel()).isEqualTo(MarginLevel.MODERATE);
    }

    @Test
    void entry_marginLevel_profitable_whenPercentAtLeast20() {
        var e = entry(1250, 1000); // margin = 25%
        assertThat(e.marginLevel()).isEqualTo(MarginLevel.PROFITABLE);
    }
}
