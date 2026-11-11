package com.keevo.inventory.stock.domain.port.in;

import com.keevo.inventory.stock.domain.model.StockReadScope;

/**
 * Query to retrieve the aggregated stock summary of the active stores the reader may see.
 * Story 3.2; Story 16.13 (reprise) — {@code scope}: the owner gets every store, a store member only their own.
 *
 * @param scope stores readable by the actor; {@code null} fails closed ({@link StockReadScope#NONE})
 */
public record GetMultiStoreOverviewQuery(StockReadScope scope) {

    public GetMultiStoreOverviewQuery {
        scope = scope == null ? StockReadScope.NONE : scope;
    }
}
