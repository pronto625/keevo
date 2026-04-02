package com.keevo.reporting.profitability.adapter.in.rest.dto;

import com.keevo.reporting.profitability.domain.model.ProductProfitabilityDetail;

import java.util.List;
import java.util.UUID;

/**
 * ProductProfitabilityDetailDto — REST response for single product detail.
 * Story 7.4, Task 9.2.
 */
public record ProductProfitabilityDetailDto(
        UUID   productId,
        String productName,
        String categoryName,
        int    unitsSold,
        long   totalRevenue,
        long   totalCost,
        long   grossMarginXaf,
        double marginPercent,
        boolean isLoss,
        String marginLevel,

        int    currentCataloguePrice,
        int    currentBuyPrice,
        int    currentTransportCost,
        int    minAppliedPrice,
        int    maxAppliedPrice,
        double avgAppliedPrice,

        List<DailyMarginEntryDto> dailyMarginLast7,
        UUID   topStoreId,
        String topStoreName,
        int    topStoreUnitsSold
) {
    public record DailyMarginEntryDto(String date, long marginXaf) {}

    public static ProductProfitabilityDetailDto from(ProductProfitabilityDetail d) {
        List<DailyMarginEntryDto> sparkline = d.dailyMarginLast7() == null
                ? List.of()
                : d.dailyMarginLast7().stream()
                .map(e -> new DailyMarginEntryDto(e.date(), e.marginXaf()))
                .toList();
        return new ProductProfitabilityDetailDto(
                d.productId(), d.productName(), d.categoryName(),
                d.unitsSold(), d.totalRevenue(), d.totalCost(),
                d.grossMarginXaf(), d.marginPercent(), d.isLoss(),
                d.marginLevel().name(),
                d.currentCataloguePrice(), d.currentBuyPrice(), d.currentTransportCost(),
                d.minAppliedPrice(), d.maxAppliedPrice(), d.avgAppliedPrice(),
                sparkline,
                d.topStoreId(), d.topStoreName(), d.topStoreUnitsSold()
        );
    }
}
