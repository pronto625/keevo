package com.keevo.reporting.profitability.domain.service;

import com.keevo.reporting.profitability.domain.model.StorePerformanceEntry;

import java.util.List;

/**
 * StoreRankingStrategy — GoF Strategy interface for store comparison ranking.
 * Story 7.4, Task 5.7.
 */
@FunctionalInterface
public interface StoreRankingStrategy {
    /**
     * Ranks the given store entries, assigning rank 1 to the best performer.
     * Returns a new list; the input is not modified.
     */
    List<StorePerformanceEntry> rank(List<StorePerformanceEntry> entries);
}
