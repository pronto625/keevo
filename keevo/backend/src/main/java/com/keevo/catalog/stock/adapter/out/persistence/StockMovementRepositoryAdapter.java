package com.keevo.catalog.stock.adapter.out.persistence;

import com.keevo.catalog.stock.domain.entity.MovementType;
import com.keevo.catalog.stock.domain.entity.StockMovement;
import com.keevo.catalog.stock.domain.port.out.StockMovementRepository;
import com.keevo.shared.infrastructure.persistence.entity.StockMovementJpaEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * StockMovementRepositoryAdapter — JPA implementation of StockMovementRepository port.
 *
 * <p>GoF: Adapter pattern.
 * Story 2.3.
 */
@Component
public class StockMovementRepositoryAdapter implements StockMovementRepository {

    private final StockMovementSpringRepository springRepository;

    public StockMovementRepositoryAdapter(StockMovementSpringRepository springRepository) {
        this.springRepository = springRepository;
    }

    @Override
    @Transactional
    public StockMovement save(StockMovement movement) {
        var entity = toJpaEntity(movement);
        var saved = springRepository.save(entity);
        return toDomain(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<StockMovement> findByProductId(UUID productId, MovementType movementType,
                                               Instant from, Instant to, UUID storeId,
                                               Pageable pageable) {
        return springRepository.findByProductIdWithFilters(productId, movementType, from, to, storeId, pageable)
                .map(this::toDomain);
    }

    // ── Conversion ────────────────────────────────────────────────────────────

    private StockMovement toDomain(StockMovementJpaEntity e) {
        return new StockMovement(
            e.getId(),
            e.getProductId(),
            e.getVariantId(),
            e.getStoreId(),
            e.getMovementType(),
            e.getQuantityBefore(),
            e.getQuantityChange(),
            e.getQuantityAfter(),
            e.getActorId(),
            e.getNotes(),
            e.getOccurredAt()
        );
    }

    private StockMovementJpaEntity toJpaEntity(StockMovement m) {
        return new StockMovementJpaEntity(
            m.getId() != null ? m.getId() : UUID.randomUUID(),
            m.getProductId(),
            m.getVariantId(),
            m.getStoreId(),
            m.getMovementType(),
            m.getQuantityBefore(),
            m.getQuantityChange(),
            m.getQuantityAfter(),
            m.getActorId(),
            m.getNotes(),
            m.getOccurredAt() != null ? m.getOccurredAt() : Instant.now()
        );
    }
}
