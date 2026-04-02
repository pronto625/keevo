package com.keevo.reporting.profitability.domain.service;

import com.keevo.reporting.profitability.domain.model.RankingMetric;

/**
 * StoreRankingStrategyFactory — GoF Factory Method for store ranking strategies.
 * Story 7.4, Task 5.7.
 */
public final class StoreRankingStrategyFactory {

    private StoreRankingStrategyFactory() {}

    public static StoreRankingStrategy of(RankingMetric metric) {
        return switch (metric) {
            case SALES_COUNT -> new BySalesCountStrategy();
            case AVG_BASKET  -> new ByAvgBasketStrategy();
            default          -> new ByCaStoreStrategy();
        };
    }
}
