package com.keevo.catalog.stock.adapter.out.persistence;

import com.keevo.shared.infrastructure.persistence.entity.StockMovementJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.UUID;

/**
 * StockMovementSpringRepository — Spring Data JPA repository for stock_movements.
 *
 * <p>Uses {@link JpaSpecificationExecutor} to support dynamic filtering without
 * JPQL nullable-param patterns that are unreliable with Hibernate 6
 * (passing null for typed parameters causes type-inference failures).
 * Filters are built programmatically in {@link StockMovementRepositoryAdapter}.
 *
 * Story 2.3.
 */
public interface StockMovementSpringRepository
        extends JpaRepository<StockMovementJpaEntity, UUID>,
                JpaSpecificationExecutor<StockMovementJpaEntity> {
    // All filtering is done via Specification — see StockMovementRepositoryAdapter
}
