package com.keevo.reporting.profitability.adapter.in.rest.dto;

import com.keevo.reporting.profitability.domain.model.MarginLevel;
import com.keevo.reporting.profitability.domain.model.ProductProfitabilityEntry;

import java.util.UUID;

/**
 * ProductProfitabilityEntryDto — REST response for profitability list items.
 * Story 7.4, Task 9.2.
 */
public record ProductProfitabilityEntryDto(
        UUID   productId,
        String productName,
        String categoryName,
        int    unitsSold,
        long   totalRevenue,
        long   totalCost,
        long   grossMarginXaf,
        double marginPercent,
        boolean isLoss,
        String marginLevel
) {
    public static ProductProfitabilityEntryDto from(ProductProfitabilityEntry e) {
        return new ProductProfitabilityEntryDto(
                e.productId(), e.productName(), e.categoryName(),
                e.unitsSold(), e.totalRevenue(), e.totalCost(),
                e.grossMarginXaf(), e.marginPercent(), e.isLoss(),
                e.marginLevel().name()
        );
    }
}
