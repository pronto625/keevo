package com.keevo.catalog.stock.domain.port.out;

import com.keevo.catalog.stock.domain.model.StoreProductStockEntry;
import com.keevo.catalog.stock.domain.model.StoreStockSummary;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.UUID;

/**
 * MultiStoreStockRepository — out port for multi-store stock aggregation queries.
 *
 * <p>Implementation uses native SQL (JdbcTemplate) to avoid cross-module
 * JPA entity imports (stores/products/stock_levels are in separate modules).
 * Story 3.2.
 */
public interface MultiStoreStockRepository {

    /**
     * Returns aggregated stock summary for ALL active stores in the current tenant.
     * Native SQL: JOIN stores + stock_levels + products, GROUP BY store.
     */
    List<StoreStockSummary> getStoreOverviews();

    /**
     * Returns paginated product stock entries for a specific store.
     * Sort: low/critical first (when sortLowFirst), then alphabetical by product name.
     */
    Page<StoreProductStockEntry> getStoreStockDetail(UUID storeId, boolean sortLowFirst, Pageable pageable);
}
