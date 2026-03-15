package com.keevo.catalog.stock.adapter.out.persistence;

import com.keevo.catalog.stock.domain.model.StockTransfer;
import com.keevo.catalog.stock.domain.model.StockTransfer.TransferStatus;
import com.keevo.catalog.stock.domain.port.out.StockTransferRepository;
import com.keevo.shared.infrastructure.persistence.entity.StockTransferJpaEntity;
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
 * StockTransferRepositoryAdapter — JPA implementation of the StockTransferRepository port.
 *
 * <p>Follows the same Specification-based pattern as StockMovementRepositoryAdapter
 * to avoid Hibernate 6 HQL nullable-param issues.
 *
 * <p>GoF: Adapter pattern.
 * Story 3.3.
 */
@Component
public class StockTransferRepositoryAdapter implements StockTransferRepository {

    private final StockTransferSpringRepository springRepository;

    public StockTransferRepositoryAdapter(StockTransferSpringRepository springRepository) {
        this.springRepository = springRepository;
    }

    @Override
    @Transactional
    public StockTransfer save(StockTransfer transfer) {
        var entity = toJpaEntity(transfer);
        var saved = springRepository.save(entity);
        return toDomain(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public java.util.Optional<StockTransfer> findById(java.util.UUID id) {
        return springRepository.findById(id).map(this::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<StockTransfer> findByFilters(UUID sourceStoreId, UUID destinationStoreId,
                                             Instant from, Instant to, Pageable pageable) {
        Specification<StockTransferJpaEntity> spec =
                buildSpec(sourceStoreId, destinationStoreId, from, to);
        return springRepository.findAll(spec, pageable).map(this::toDomain);
    }

    // ── Specification builder ────────────────────────────────────────────────

    private Specification<StockTransferJpaEntity> buildSpec(
            UUID sourceStoreId, UUID destinationStoreId, Instant from, Instant to) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (sourceStoreId != null) {
                predicates.add(cb.equal(root.get("sourceStoreId"), sourceStoreId));
            }
            if (destinationStoreId != null) {
                predicates.add(cb.equal(root.get("destinationStoreId"), destinationStoreId));
            }
            if (from != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("occurredAt"), from));
            }
            if (to != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("occurredAt"), to));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    // ── Conversion ────────────────────────────────────────────────────────────

    private StockTransfer toDomain(StockTransferJpaEntity e) {
        return new StockTransfer(
                e.getId(),
                e.getSourceStoreId(),
                e.getDestinationStoreId(),
                e.getProductId(),
                e.getVariantId(),
                e.getQuantity(),
                e.getActorId(),
                e.getOccurredAt(),
                e.getStatus(),
                e.getNotes()
        );
    }

    private StockTransferJpaEntity toJpaEntity(StockTransfer t) {
        return new StockTransferJpaEntity(
                t.getId() != null ? t.getId() : UUID.randomUUID(),
                t.getSourceStoreId(),
                t.getDestinationStoreId(),
                t.getProductId(),
                t.getVariantId(),
                t.getQuantity(),
                t.getActorId(),
                t.getOccurredAt() != null ? t.getOccurredAt() : Instant.now(),
                t.getStatus() != null ? t.getStatus() : TransferStatus.COMPLETED,
                t.getNotes()
        );
    }
}
