package com.keevo.inventory.stock.domain.port.out;

import com.keevo.inventory.stock.domain.model.CrossStoreAvailabilityEntry;
import com.keevo.inventory.stock.domain.model.StoreProductStockEntry;
import com.keevo.inventory.stock.domain.model.StoreStockSummary;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.UUID;

/**
 * MultiStoreStockRepository — out port for multi-store stock aggregation queries.
 *
 * <p>Implementation uses native SQL (JdbcTemplate) to avoid cross-module
 * JPA entity imports (stores/products/stock_levels are in separate modules).
 * Story 3.2. Extended in Story 3.4 with cross-store availability query. Story 16.13 (reprise): every multi-store read
 * takes an optional single-store filter (a store member's scope), applied in SQL.
 */
public interface MultiStoreStockRepository {

    /**
     * Aggregated stock summary of the active stores of the current tenant — every one when {@code onlyStoreId} is
     * {@code null}, else that store only (empty when it is not an active store of the tenant). One set-based query:
     * {@code stock_levels} aggregated per store, then joined to {@code stores}.
     */
    List<StoreStockSummary> getStoreOverviews(UUID onlyStoreId);

    /**
     * Returns paginated product stock entries for a specific store.
     * Sort: low/critical first (when sortLowFirst), then alphabetical by product name.
     */
    Page<StoreProductStockEntry> getStoreStockDetail(UUID storeId, boolean sortLowFirst, boolean lowOnly, Pageable pageable);

    /**
     * Stock availability for {@code productId} across the active stores — every one when {@code onlyStoreId} is
     * {@code null}, else that store only. Includes stores with quantity = 0. Sorted by store created_at ASC.
     * Story 3.4.
     */
    List<CrossStoreAvailabilityEntry> getProductAvailability(UUID productId, UUID onlyStoreId);
}
