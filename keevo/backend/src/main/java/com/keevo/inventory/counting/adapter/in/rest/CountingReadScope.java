package com.keevo.inventory.counting.adapter.in.rest;

import com.keevo.inventory.counting.domain.port.out.InventorySessionRepository;
import com.keevo.inventory.stock.adapter.in.StockReadScopes;
import com.keevo.inventory.stock.domain.model.StockReadScope;
import com.keevo.shared.domain.exception.DomainException;

import java.util.UUID;

/**
 * Story 16.13 (reprise 10/11/2026, D1) — the store scope of the inventory-counting READS (theoretical and physical
 * stock, gaps): a store member (MANAGER, EMPLOYEE, VIEWER) only reads the sessions of their own store, the OWNER every
 * store. Same rule and message as the multi-store stock reads ({@link StockReadScope}).
 */
final class CountingReadScope {

    private CountingReadScope() {
    }

    /**
     * One primary-key read; an unknown session is left to the use case (its existing not-found error).
     *
     * @throws DomainException FORBIDDEN (403, French message) when the session belongs to a store outside the scope
     */
    static void requireSessionReadable(InventorySessionRepository sessions, UUID sessionId) {
        StockReadScope scope = StockReadScopes.current();
        if (!scope.restricted()) {
            return;
        }
        sessions.findById(sessionId).ifPresent(session -> scope.requireReadable(session.getStoreId()));
    }

    /**
     * @throws DomainException FORBIDDEN (403, French message) when {@code storeId} is outside the scope
     */
    static void requireStoreReadable(UUID storeId) {
        StockReadScope scope = StockReadScopes.current();
        if (scope.restricted()) {
            scope.requireReadable(storeId);
        }
    }

    /**
     * The store a session list must use: the requested one when readable, the member's own store when none was
     * requested ({@code null} = every store, owner only).
     *
     * @throws DomainException FORBIDDEN (403, French message) for a store outside the scope
     */
    static UUID readableStore(UUID requested) {
        return StockReadScopes.current().requireReadable(requested);
    }
}
