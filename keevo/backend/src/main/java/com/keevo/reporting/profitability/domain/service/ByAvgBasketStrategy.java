package com.keevo.reporting.profitability.domain.service;

import com.keevo.reporting.profitability.domain.model.StorePerformanceEntry;

import java.util.Comparator;
import java.util.List;

/**
 * ByAvgBasketStrategy — rank stores by averageBasket descending.
 * Story 7.4, Task 5.7.
 */
public class ByAvgBasketStrategy implements StoreRankingStrategy {

    @Override
    public List<StorePerformanceEntry> rank(List<StorePerformanceEntry> entries) {
        var sorted = entries.stream()
                .sorted(Comparator.comparingLong(StorePerformanceEntry::averageBasket).reversed())
                .toList();
        return ByCaStoreStrategy.assignRanks(sorted);
    }
}
