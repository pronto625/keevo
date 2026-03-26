package com.keevo.catalog.stock.adapter.out.persistence;

import com.keevo.catalog.stock.domain.entity.StockLevel;
import com.keevo.catalog.stock.domain.port.out.StockLevelRepository;
import com.keevo.shared.infrastructure.persistence.entity.StockLevelJpaEntity;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * StockLevelRepositoryAdapter — JPA implementation of StockLevelRepository port.
 *
 * <p>GoF: Adapter — converts between domain StockLevel and JPA entity.
 * Story 2.3.
 */
@Component
public class StockLevelRepositoryAdapter implements StockLevelRepository {

    private final StockLevelSpringRepository springRepository;

    public StockLevelRepositoryAdapter(StockLevelSpringRepository springRepository) {
        this.springRepository = springRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<StockLevel> findByProductAndStore(UUID productId, UUID storeId) {
        return springRepository.findByProductAndStore(productId, storeId)
                .map(this::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<StockLevel> findByProductVariantAndStore(UUID productId, UUID variantId, UUID storeId) {
        return springRepository.findByProductVariantAndStore(productId, variantId, storeId)
                .map(this::toDomain);
    }

    @Override
    @Transactional
    public StockLevel save(StockLevel level) {
        var entity = toJpaEntity(level);
        var saved = springRepository.save(entity);
        return toDomain(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public List<StockLevel> findAllByProduct(UUID productId) {
        return springRepository.findAllByProduct(productId).stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<StockLevel> findAllByStoreId(UUID storeId) {
        return springRepository.findAllByStoreId(storeId).stream()
                .map(this::toDomain)
                .toList();
    }

    // ── Conversion ────────────────────────────────────────────────────────────

    private StockLevel toDomain(StockLevelJpaEntity e) {
        return new StockLevel(
            e.getId(),
            e.getProductId(),
            e.getVariantId(),
            e.getStoreId(),
            e.getQuantity() != null ? e.getQuantity() : 0,
            e.getUpdatedAt()
        );
    }

    private StockLevelJpaEntity toJpaEntity(StockLevel level) {
        return new StockLevelJpaEntity(
            level.getId() != null ? level.getId() : UUID.randomUUID(),
            level.getProductId(),
            level.getVariantId(),
            level.getStoreId(),
            level.getQuantity(),
            level.getUpdatedAt() != null ? level.getUpdatedAt() : Instant.now()
        );
    }
}
