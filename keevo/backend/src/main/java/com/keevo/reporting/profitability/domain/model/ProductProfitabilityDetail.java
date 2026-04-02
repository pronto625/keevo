package com.keevo.reporting.profitability.domain.model;

import java.util.List;
import java.util.UUID;

/**
 * ProductProfitabilityDetail — extends the list entry with cost snapshot,
 * price override stats, sparkline data, and top-performing store.
 *
 * Story 7.4, Task 4.2.
 */
public record ProductProfitabilityDetail(
        UUID    productId,
        String  productName,
        String  categoryName,
        int     unitsSold,
        long    totalRevenue,
        long    totalCost,
        long    grossMarginXaf,
        double  marginPercent,
        boolean isLoss,
        UUID    storeId,

        // Current catalogue cost snapshot
        int     currentCataloguePrice,
        int     currentBuyPrice,
        int     currentTransportCost,

        // Price override stats (from sale_items.applied_unit_price)
        int     minAppliedPrice,
        int     maxAppliedPrice,
        double  avgAppliedPrice,

        // Last-7-day margin sparkline
        List<DailyMarginEntry> dailyMarginLast7,

        // Top store by units sold in this period
        UUID    topStoreId,
        String  topStoreName,
        int     topStoreUnitsSold
) {
    /** Daily margin data point for the 7-day sparkline. */
    public record DailyMarginEntry(String date, long marginXaf) {}

    public MarginLevel marginLevel() {
        return MarginLevel.of(marginPercent, isLoss);
    }
}
