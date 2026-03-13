package com.keevo.store.store.domain.port.out;

import com.keevo.store.store.domain.model.Store;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * StoreRepository — Output port for store persistence.
 *
 * <p>Pure Java interface — no Spring or JPA imports.
 * Story 3.1 — Store & Warehouse management.
 */
public interface StoreRepository {

    Store save(Store store);

    Optional<Store> findById(UUID storeId);

    List<Store> findAll();

    List<Store> findAllActive();

    int countActive();

    /** Returns true if any store with type=WAREHOUSE exists for the current tenant. */
    boolean existsWarehouse();
}
