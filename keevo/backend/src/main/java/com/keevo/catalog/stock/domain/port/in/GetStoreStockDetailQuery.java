package com.keevo.catalog.stock.domain.port.in;

import java.util.UUID;

/**
 * Query to retrieve paginated product stock list for a specific store.
 *
 * @param storeId      target store UUID
 * @param page         0-based page index
 * @param size         page size (default 25)
 * @param sortLowFirst true → low/critical stock entries first, then alphabetical
 * @param lowOnly      true → return only products at or below their stock threshold
 *
 * Story 3.2.
 */
public record GetStoreStockDetailQuery(UUID storeId, int page, int size, boolean sortLowFirst, boolean lowOnly) {}
