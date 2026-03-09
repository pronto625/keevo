package com.keevo.catalog.stock.adapter.out.persistence;

import com.keevo.catalog.stock.domain.entity.MovementType;
import com.keevo.catalog.stock.domain.entity.StockMovement;
import com.keevo.catalog.stock.domain.port.out.StockMovementRepository;
import com.keevo.shared.infrastructure.persistence.entity.StockMovementJpaEntity;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * StockMovementRepositoryAdapter — JPA implementation of StockMovementRepository port.
 *
 * <p>Uses JpaSpecificationExecutor + programmatic Specification building to avoid
 * Hibernate 6 JPQL nullable-param issues (:param IS NULL patterns break with
 * typed null parameters in HQL).
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
        Specification<StockMovementJpaEntity> spec = buildSpec(productId, movementType, from, to, storeId);
        return springRepository.findAll(spec, pageable).map(this::toDomain);
    }

    // ── Specification builder ─────────────────────────────────────────────────

    private Specification<StockMovementJpaEntity> buildSpec(
            UUID productId,
            MovementType movementType,
            Instant from,
            Instant to,
            UUID storeId) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            // productId is always required
            predicates.add(cb.equal(root.get("productId"), productId));

            // optional filters — only add predicate if param is non-null
            if (movementType != null) {
                predicates.add(cb.equal(root.get("movementType"), movementType));
            }
            if (from != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("occurredAt"), from));
            }
            if (to != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("occurredAt"), to));
            }
            if (storeId != null) {
                predicates.add(cb.equal(root.get("storeId"), storeId));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };
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
