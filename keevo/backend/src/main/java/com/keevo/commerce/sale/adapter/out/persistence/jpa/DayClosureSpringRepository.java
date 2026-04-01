package com.keevo.commerce.sale.adapter.out.persistence.jpa;

import com.keevo.commerce.sale.adapter.out.persistence.entity.DayClosureJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * DayClosureSpringRepository — Spring Data JPA interface for day_closures table.
 * Story 4.4 — Clôture Journalière & Historique des Ventes
 *
 * <p>Infrastructure layer only. Consumed by {@link 
 * com.keevo.commerce.sale.adapter.out.persistence.impl.DayClosureRepositoryAdapter}.
 */
@Repository
public interface DayClosureSpringRepository extends JpaRepository<DayClosureJpaEntity, UUID> {

    /**
     * Check if a closure exists for a store on a given date.
     */
    boolean existsByStoreIdAndClosureDate(UUID storeId, LocalDate closureDate);

    /**
     * Find all closures for a store on a given date.
     */
    List<DayClosureJpaEntity> findByStoreIdAndClosureDate(UUID storeId, LocalDate closureDate);

    /**
     * Find the most recent closure for a store across all dates.
     * Spring Data derives: SELECT * FROM day_closures WHERE store_id = ? ORDER BY closed_at DESC LIMIT 1
     */
    Optional<DayClosureJpaEntity> findFirstByStoreIdOrderByClosedAtDesc(UUID storeId);
}
