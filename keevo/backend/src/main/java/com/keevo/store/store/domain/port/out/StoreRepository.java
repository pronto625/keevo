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

    /** Story 19.12 — the store row locked ({@code FOR UPDATE}) until the transaction ends (logo changes). */
    Optional<Store> findByIdForUpdate(UUID storeId);

    List<Store> findAll();

    List<Store> findAllActive();

    /** Story 20.14 — the stores among {@code ids}, active or not (one query). */
    List<Store> findAllById(java.util.Collection<UUID> ids);

    int countActive();

    /** Story 20.5 — the country of the oldest active store (empty when there is none). One row read. */
    Optional<String> oldestActiveCountry();

    /**
     * Story 20.14 — the default store of the tenant: the active shops before the warehouses, then the oldest, then
     * the id; empty without active store. One row read.
     */
    Optional<UUID> defaultStoreId();

    /** Returns true if any store with type=WAREHOUSE exists for the current tenant. */
    boolean existsWarehouse();
}
