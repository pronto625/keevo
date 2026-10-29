package com.keevo.inventory.stock.adapter.in.web.dto;

import com.keevo.inventory.stock.domain.model.StoreStockSummary;
import com.keevo.store.api.StoreType;

import java.util.UUID;

/**
 * REST response for a store's aggregated stock summary.
 * Story 3.2.
 */
public record StoreStockSummaryDto(
    UUID storeId,
    String storeName,
    StoreType storeType,
    int productCount,
    long totalValueXaf,
    int lowStockCount
) {
    public static StoreStockSummaryDto from(StoreStockSummary s) {
        return new StoreStockSummaryDto(
            s.storeId(), s.storeName(), s.storeType(),
            s.productCount(), s.totalValueXaf(), s.lowStockCount()
        );
    }
}
