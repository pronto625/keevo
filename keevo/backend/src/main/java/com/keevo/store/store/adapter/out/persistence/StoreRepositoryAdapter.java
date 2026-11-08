package com.keevo.store.store.adapter.out.persistence;

import com.keevo.store.api.StoreCategory;
import com.keevo.store.store.domain.model.Store;
import com.keevo.store.api.StoreType;
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
        // Flushed now: updated_at is stamped by @PreUpdate at flush time; left to the commit it would
        // be later than the sync log row of the operation that wrote it, and last-write-wins would
        // take that sync write for a server-side one (see LastWriteWinsStrategy).
        StoreJpaEntity saved = jpa.saveAndFlush(entity);
        return entityToDomain(saved);
    }

    @Override
    public Optional<Store> findById(UUID storeId) {
        return jpa.findById(storeId).map(this::entityToDomain);
    }

    @Override
    public Optional<Store> findByIdForUpdate(UUID storeId) {
        return jpa.findByIdForUpdate(storeId).map(this::entityToDomain);
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
    public List<Store> findAllById(java.util.Collection<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return jpa.findAllById(ids).stream().map(this::entityToDomain).toList();
    }

    @Override
    public int countActive() {
        return (int) jpa.countByIsActiveTrue();
    }

    @Override
    public Optional<String> oldestActiveCountry() {
        return jpa.findActiveCountriesOldestFirst(org.springframework.data.domain.PageRequest.of(0, 1)).stream()
                .findFirst();
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
                e.getUpdatedAt(),
                StoreCategory.fromKey(e.getCategory()).orElse(null), // an unknown key (newer list) reads as none
                e.getDescription(),
                e.getLogoPath(),
                e.getNiu(),
                e.getRccm(),
                e.getCountry(),
                e.isShareClients()
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
        e.setCategory(store.category() == null ? null : store.category().key());
        e.setDescription(store.description());
        e.setLogoPath(store.logoPath());
        e.setNiu(store.niu());
        e.setRccm(store.rccm());
        e.setCountry(store.country());
        e.setShareClients(store.shareClients());
        return e;
    }
}
