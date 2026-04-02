package com.keevo.reporting.profitability.domain.model;

/**
 * RankingMetric — store comparison ranking dimension.
 * Story 7.4, Task 4.5.
 */
public enum RankingMetric {
    CA,
    SALES_COUNT,
    AVG_BASKET;

    public static RankingMetric fromString(String s) {
        if (s == null) return CA;
        try {
            return RankingMetric.valueOf(s.toUpperCase());
        } catch (IllegalArgumentException e) {
            return CA;
        }
    }
}
