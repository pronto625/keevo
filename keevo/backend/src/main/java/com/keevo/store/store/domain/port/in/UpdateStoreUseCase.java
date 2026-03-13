package com.keevo.store.store.domain.port.in;

import com.keevo.store.store.domain.model.Store;

/**
 * UpdateStoreUseCase — Input port for store update.
 * Story 3.1 — AC4.
 */
public interface UpdateStoreUseCase {
    Store execute(UpdateStoreCommand command);
}
