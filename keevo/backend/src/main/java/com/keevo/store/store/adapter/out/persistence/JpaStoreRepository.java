package com.keevo.store.store.adapter.out.persistence;

import com.keevo.store.api.StoreType;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * JpaStoreRepository — Spring Data JPA repository for StoreJpaEntity.
 * Story 3.1.
 */
public interface JpaStoreRepository extends JpaRepository<StoreJpaEntity, UUID> {

    List<StoreJpaEntity> findByIsActiveTrue();

    long countByIsActiveTrue();

    boolean existsByType(StoreType type);

    /** Story 19.12 — {@code SELECT … FOR UPDATE} of one store (serialises logo changes of that store). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM StoreJpaEntity s WHERE s.id = :id")
    Optional<StoreJpaEntity> findByIdForUpdate(@Param("id") UUID id);
}
