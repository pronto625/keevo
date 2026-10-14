package com.keevo.reporting.profitability.domain.model;

import java.util.UUID;

/**
 * StorePerformanceEntry — ranked performance snapshot for one store.
 * Story 7.4, Task 4.3.
 */
public record StorePerformanceEntry(
        int    rank,
        UUID   storeId,
        String storeName,
        long   totalRevenue,
        int    salesCount,
        long   averageBasket,
        String topProductName,
        Double deltaPercent   // vs same-length preceding period; positive = improvement; null = no data in that period (A33)
) {}
