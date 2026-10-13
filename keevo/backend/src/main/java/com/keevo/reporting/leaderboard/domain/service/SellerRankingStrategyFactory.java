package com.keevo.reporting.leaderboard.domain.service;

import com.keevo.reporting.profitability.domain.model.RankingMetric;

/**
 * SellerRankingStrategyFactory — GoF Factory Method for seller ranking strategies (v1s-14-2),
 * mirroring {@code StoreRankingStrategyFactory}. Null / CA → revenue.
 */
public final class SellerRankingStrategyFactory {

    private SellerRankingStrategyFactory() {}

    public static SellerRankingStrategy of(RankingMetric metric) {
        if (metric == null) return new BySellerRevenueStrategy();
        return switch (metric) {
            case SALES_COUNT -> new BySellerSalesCountStrategy();
            case AVG_BASKET  -> new BySellerAvgBasketStrategy();
            default          -> new BySellerRevenueStrategy();
        };
    }
}
