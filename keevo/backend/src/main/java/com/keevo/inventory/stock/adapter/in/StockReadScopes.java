package com.keevo.inventory.stock.adapter.in;

import com.keevo.inventory.stock.domain.model.StockReadScope;
import com.keevo.shared.infrastructure.security.CurrentActor;

/**
 * Story 16.13 (reprise 10/11/2026, user decision D1) — the only place where the stock read scope is resolved from the
 * authenticated actor, by the inbound adapters (REST controllers, AI tools): the OWNER sees every store; a store
 * member (MANAGER, EMPLOYEE, VIEWER) only their assigned store, nothing without one; anyone else nothing (fail closed).
 */
public final class StockReadScopes {

    private StockReadScopes() {
    }

    /** The current actor's scope. */
    public static StockReadScope current() {
        if (CurrentActor.isOwner()) {
            return StockReadScope.ALL;
        }
        if (CurrentActor.isStoreMember()) {
            return StockReadScope.store(CurrentActor.assignedStoreIdOrNull());
        }
        return StockReadScope.NONE;
    }
}
