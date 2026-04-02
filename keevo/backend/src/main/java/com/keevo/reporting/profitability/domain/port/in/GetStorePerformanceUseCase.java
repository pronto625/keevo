package com.keevo.reporting.profitability.domain.port.in;

import com.keevo.reporting.profitability.domain.model.RankingMetric;
import com.keevo.reporting.profitability.domain.model.StorePerformanceEntry;

import java.time.LocalDate;
import java.util.List;

/**
 * GetStorePerformanceUseCase — inbound port for store comparative ranking.
 * Story 7.4, Task 6.2.
 */
public interface GetStorePerformanceUseCase {

    record StorePerformanceQuery(
            String tenantId,
            LocalDate from,
            LocalDate to,
            RankingMetric metric
    ) {}

    /**
     * Returns a ranked list of stores for the given period and ranking metric.
     * Each entry includes a deltaPercent vs the equivalent preceding period.
     */
    List<StorePerformanceEntry> getRanking(StorePerformanceQuery query);
}
