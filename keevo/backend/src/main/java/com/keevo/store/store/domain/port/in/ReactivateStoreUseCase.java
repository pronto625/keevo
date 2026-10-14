package com.keevo.store.store.domain.port.in;

import com.keevo.store.store.domain.model.Store;

/**
 * ReactivateStoreUseCase — Input port to reactivate a deactivated store.
 */
public interface ReactivateStoreUseCase {
    Store execute(ReactivateStoreCommand command);
}
