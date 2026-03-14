package com.keevo.catalog.stock.domain.port.in;

import com.keevo.catalog.stock.domain.model.StoreStockSummary;

import java.util.List;

/**
 * Use case for retrieving aggregated stock summary for all active stores.
 * Story 3.2.
 */
public interface GetMultiStoreOverviewUseCase {
    List<StoreStockSummary> execute(GetMultiStoreOverviewQuery query);
}
