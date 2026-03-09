package com.keevo.catalog.stock.adapter.out.persistence;

import com.keevo.shared.infrastructure.persistence.entity.StockMovementJpaEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.UUID;

/**
 * StockMovementSpringRepository — Spring Data JPA repository for stock_movements.
 */
public interface StockMovementSpringRepository extends JpaRepository<StockMovementJpaEntity, UUID> {

    /**
     * Paginated history with optional nullable filters.
     * NULL parameters are treated as "no filter" using COALESCE / IS NULL logic.
     */
    @Query("""
        SELECT m FROM StockMovementJpaEntity m
        WHERE m.productId = :productId
          AND (:movementType IS NULL OR m.movementType = :movementType)
          AND (:from IS NULL OR m.occurredAt >= :from)
          AND (:to IS NULL OR m.occurredAt <= :to)
          AND (:storeId IS NULL OR m.storeId = :storeId)
        ORDER BY m.occurredAt DESC
        """)
    Page<StockMovementJpaEntity> findByProductIdWithFilters(
            @Param("productId") UUID productId,
            @Param("movementType") com.keevo.catalog.stock.domain.entity.MovementType movementType,
            @Param("from") Instant from,
            @Param("to") Instant to,
            @Param("storeId") UUID storeId,
            Pageable pageable);
}
