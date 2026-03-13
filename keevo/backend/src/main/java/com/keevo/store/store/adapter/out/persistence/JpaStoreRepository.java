package com.keevo.store.store.adapter.out.persistence;

import com.keevo.store.store.domain.model.StoreType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * JpaStoreRepository — Spring Data JPA repository for StoreJpaEntity.
 * Story 3.1.
 */
public interface JpaStoreRepository extends JpaRepository<StoreJpaEntity, UUID> {

    List<StoreJpaEntity> findByIsActiveTrue();

    long countByIsActiveTrue();

    boolean existsByType(StoreType type);
}
