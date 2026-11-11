package com.keevo.inventory.stock.domain.port.in;

import com.keevo.inventory.stock.domain.model.StockReadScope;

import java.util.UUID;

/**
 * Query to retrieve paginated product stock list for a specific store.
 *
 * @param storeId      target store UUID
 * @param page         0-based page index
 * @param size         page size (default 25)
 * @param sortLowFirst true → low/critical stock entries first, then alphabetical
 * @param lowOnly      true → return only products at or below their stock threshold
 * @param scope        Story 16.13 (reprise) — stores readable by the actor: a store outside it is refused (403);
 *                     {@code null} fails closed ({@link StockReadScope#NONE})
 *
 * Story 3.2.
 */
public record GetStoreStockDetailQuery(UUID storeId, int page, int size, boolean sortLowFirst, boolean lowOnly,
                                       StockReadScope scope) {

    public GetStoreStockDetailQuery {
        scope = scope == null ? StockReadScope.NONE : scope;
    }
}
