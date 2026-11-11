package com.keevo.inventory.stock.domain.port.in;

import com.keevo.inventory.stock.domain.model.StockReadScope;

import java.util.UUID;

/**
 * GetCrossStoreAvailabilityQuery — input record for the cross-store availability use case.
 *
 * <p>GoF: Query Object. The use case is driven purely by this immutable record,
 * making it MCP-ready (plain Java, no framework dependency).
 *
 * <p>Story 16.13 (reprise) — {@code scope}: the owner sees every store, a store member only their own store's line;
 * {@code null} fails closed ({@link StockReadScope#NONE}).
 *
 * Story 3.4.
 */
public record GetCrossStoreAvailabilityQuery(UUID productId, StockReadScope scope) {

    public GetCrossStoreAvailabilityQuery {
        scope = scope == null ? StockReadScope.NONE : scope;
    }
}
