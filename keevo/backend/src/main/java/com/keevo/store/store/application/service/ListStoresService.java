package com.keevo.store.store.application.service;

import com.keevo.store.store.domain.model.Store;
import com.keevo.store.store.domain.port.in.ListStoresQuery;
import com.keevo.store.store.domain.port.in.ListStoresUseCase;
import com.keevo.store.store.domain.port.out.StoreRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * ListStoresService — Lists stores (active only or all).
 *
 * <p>Story 3.1 — AC6.
 */
@Service
public class ListStoresService implements ListStoresUseCase {

    private final StoreRepository storeRepository;

    public ListStoresService(StoreRepository storeRepository) {
        this.storeRepository = storeRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Store> execute(ListStoresQuery query) {
        return query.includeInactive()
                ? storeRepository.findAll()
                : storeRepository.findAllActive();
    }
}
