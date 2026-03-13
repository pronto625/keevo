package com.keevo.store.store.domain.port.in;

import com.keevo.store.store.domain.model.Store;

/**
 * CreateStoreUseCase — Input port for store creation.
 * Story 3.1 — AC1, AC2, AC3.
 */
public interface CreateStoreUseCase {
    Store execute(CreateStoreCommand command);
}
