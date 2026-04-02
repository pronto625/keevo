package com.keevo.reporting.profitability.domain.model;

import java.util.UUID;

/**
 * ProductProfitabilityEntry — immutable domain record for one product's
 * profitability in a given period.
 *
 * <p>Margin formula (mirrors PricingCalculator.java):
 * <pre>
 *   totalCost       = (buyPrice + transportCost) × unitsSold
 *   grossMarginXaf  = totalRevenue - totalCost
 *   marginPercent   = (totalCost == 0) ? 0.0 : (grossMarginXaf / totalCost) × 100
 * </pre>
 *
 * Story 7.4, Task 4.1.
 */
public record ProductProfitabilityEntry(
        UUID    productId,
        String  productName,
        String  categoryName,   // nullable
        int     unitsSold,
        long    totalRevenue,
        long    totalCost,
        long    grossMarginXaf,
        double  marginPercent,
        boolean isLoss,
        UUID    storeId         // null = aggregated across all stores
) {
    /** Computed margin level — matches PricingCalculatorWidget thresholds. */
    public MarginLevel marginLevel() {
        return MarginLevel.of(marginPercent, isLoss);
    }
}
