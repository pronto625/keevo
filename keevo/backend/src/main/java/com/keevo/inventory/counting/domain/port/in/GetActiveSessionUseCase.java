package com.keevo.inventory.counting.domain.port.in;

import com.keevo.inventory.counting.domain.model.InventorySession;

import java.util.Optional;

/**
 * GetActiveSessionUseCase — Port in for retrieving the active session for a store.
 */
public interface GetActiveSessionUseCase {
    Optional<InventorySession> execute(GetActiveSessionQuery query);
}
