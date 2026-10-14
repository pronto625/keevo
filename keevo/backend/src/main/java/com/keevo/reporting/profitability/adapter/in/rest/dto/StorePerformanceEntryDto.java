package com.keevo.reporting.profitability.adapter.in.rest.dto;

import com.keevo.reporting.profitability.domain.model.StorePerformanceEntry;

import java.util.UUID;

/**
 * StorePerformanceEntryDto — REST response for store comparison list items.
 * Story 7.4, Task 9.2.
 */
public record StorePerformanceEntryDto(
        int    rank,
        UUID   storeId,
        String storeName,
        long   totalRevenue,
        int    salesCount,
        long   averageBasket,
        String topProductName,
        Double deltaPercent   // null when the preceding period has no sales (A33)
) {
    public static StorePerformanceEntryDto from(StorePerformanceEntry e) {
        return new StorePerformanceEntryDto(
                e.rank(), e.storeId(), e.storeName(),
                e.totalRevenue(), e.salesCount(), e.averageBasket(),
                e.topProductName(), e.deltaPercent()
        );
    }
}
