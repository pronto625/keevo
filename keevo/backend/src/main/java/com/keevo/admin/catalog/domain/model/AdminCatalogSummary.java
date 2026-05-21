package com.keevo.admin.catalog.domain.model;

public record AdminCatalogSummary(
        long totalProducts,
        long activeProducts,
        long draftProducts,
        long lowStockProducts,   // stock_quantity > 0 AND stock_quantity <= 5
        long outOfStockProducts, // stock_quantity = 0
        long createdThisWeek
) {}
