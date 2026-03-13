package com.keevo.store.store.domain.port.in;

import com.keevo.store.store.domain.model.Store;

import java.util.List;

/**
 * ListStoresUseCase — Input port for listing stores.
 * Story 3.1 — AC6.
 */
public interface ListStoresUseCase {
    List<Store> execute(ListStoresQuery query);
}
