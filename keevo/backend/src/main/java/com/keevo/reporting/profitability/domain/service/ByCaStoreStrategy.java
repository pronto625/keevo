package com.keevo.reporting.profitability.domain.service;

import com.keevo.reporting.profitability.domain.model.StorePerformanceEntry;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.StreamSupport;

/**
 * ByCaStoreStrategy — rank stores by totalRevenue (CA) descending.
 * Story 7.4, Task 5.7.
 */
public class ByCaStoreStrategy implements StoreRankingStrategy {

    @Override
    public List<StorePerformanceEntry> rank(List<StorePerformanceEntry> entries) {
        var sorted = entries.stream()
                .sorted(Comparator.comparingLong(StorePerformanceEntry::totalRevenue).reversed())
                .toList();
        return assignRanks(sorted);
    }

    static List<StorePerformanceEntry> assignRanks(List<StorePerformanceEntry> sorted) {
        var rank = new AtomicInteger(1);
        return sorted.stream()
                .map(e -> new StorePerformanceEntry(
                        rank.getAndIncrement(),
                        e.storeId(), e.storeName(), e.totalRevenue(),
                        e.salesCount(), e.averageBasket(), e.topProductName(), e.deltaPercent()
                ))
                .toList();
    }
}
