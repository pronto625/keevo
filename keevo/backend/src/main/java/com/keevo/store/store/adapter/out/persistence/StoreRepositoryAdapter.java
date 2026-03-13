package com.keevo.store.store.adapter.out.persistence;

import com.keevo.store.store.domain.model.Store;
import com.keevo.store.store.domain.model.StoreType;
import com.keevo.store.store.domain.port.out.StoreRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * StoreRepositoryAdapter — JPA implementation of the StoreRepository port.
 * Story 3.1.
 */
@Component
public class StoreRepositoryAdapter implements StoreRepository {

    private final JpaStoreRepository jpa;

    public StoreRepositoryAdapter(JpaStoreRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public Store save(Store store) {
        StoreJpaEntity entity = domainToEntity(store);
        StoreJpaEntity saved = jpa.save(entity);
        return entityToDomain(saved);
    }

    @Override
    public Optional<Store> findById(UUID storeId) {
        return jpa.findById(storeId).map(this::entityToDomain);
    }

    @Override
    public List<Store> findAll() {
        return jpa.findAll().stream().map(this::entityToDomain).toList();
    }

    @Override
    public List<Store> findAllActive() {
        return jpa.findByIsActiveTrue().stream().map(this::entityToDomain).toList();
    }

    @Override
    public int countActive() {
        return (int) jpa.countByIsActiveTrue();
    }

    @Override
    public boolean existsWarehouse() {
        return jpa.existsByType(StoreType.WAREHOUSE);
    }

    // ── Mapping helpers ──────────────────────────────────────────

    private Store entityToDomain(StoreJpaEntity e) {
        return new Store(
                e.getId(),
                e.getName(),
                e.getType(),
                e.getAddress(),
                e.getPhone(),
                e.isActive(),
                e.getCreatedAt(),
                e.getUpdatedAt()
        );
    }

    private StoreJpaEntity domainToEntity(Store store) {
        StoreJpaEntity e = new StoreJpaEntity();
        e.setId(store.id());
        e.setName(store.name());
        e.setType(store.type());
        e.setAddress(store.address());
        e.setPhone(store.phone());
        e.setActive(store.isActive());
        return e;
    }
}
