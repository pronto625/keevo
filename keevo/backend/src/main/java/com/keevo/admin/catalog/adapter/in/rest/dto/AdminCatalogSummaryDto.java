package com.keevo.admin.catalog.adapter.in.rest.dto;

import com.keevo.admin.catalog.domain.model.AdminCatalogSummary;

public record AdminCatalogSummaryDto(
        long totalProducts,
        long activeProducts,
        long draftProducts,
        long lowStockProducts,
        long outOfStockProducts,
        long createdThisWeek
) {
    public static AdminCatalogSummaryDto from(AdminCatalogSummary summary) {
        return new AdminCatalogSummaryDto(
                summary.totalProducts(),
                summary.activeProducts(),
                summary.draftProducts(),
                summary.lowStockProducts(),
                summary.outOfStockProducts(),
                summary.createdThisWeek()
        );
    }
}
