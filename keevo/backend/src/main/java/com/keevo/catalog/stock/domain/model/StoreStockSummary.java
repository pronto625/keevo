package com.keevo.catalog.stock.domain.model;

import com.keevo.store.store.domain.model.StoreType;
import java.util.UUID;

/**
 * StoreStockSummary — Composite root (GoF: Composite).
 * Aggregated stock snapshot for a single store across all products.
 * Story 3.2.
 */
public record StoreStockSummary(
    UUID storeId,
    String storeName,
    StoreType storeType,
    int productCount,       // distinct SKUs with any stock_level row in this store
    long totalValueXaf,     // SUM(quantity × price) across all products in this store
    int lowStockCount       // count of products where qty <= minimumThreshold AND threshold > 0
) {}
