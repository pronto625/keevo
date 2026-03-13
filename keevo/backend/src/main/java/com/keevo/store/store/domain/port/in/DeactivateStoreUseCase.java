package com.keevo.store.store.domain.port.in;

import com.keevo.store.store.domain.model.Store;

/**
 * DeactivateStoreUseCase — Input port for store deactivation.
 * Story 3.1 — AC5.
 */
public interface DeactivateStoreUseCase {
    Store execute(DeactivateStoreCommand command);
}
