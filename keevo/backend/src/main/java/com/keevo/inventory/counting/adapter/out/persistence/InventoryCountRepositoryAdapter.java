package com.keevo.inventory.counting.adapter.out.persistence;

import com.keevo.inventory.counting.domain.model.InventoryCount;
import com.keevo.inventory.counting.domain.port.out.InventoryCountRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
public class InventoryCountRepositoryAdapter implements InventoryCountRepository {

    private final JpaInventoryCountRepository jpaRepository;

    public InventoryCountRepositoryAdapter(JpaInventoryCountRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    @Transactional
    public InventoryCount save(InventoryCount count) {
        return toDomain(jpaRepository.save(toJpa(count)));
    }

    @Override
    @Transactional(readOnly = true)
    public List<InventoryCount> findBySessionId(UUID sessionId) {
        return jpaRepository.findBySessionId(sessionId).stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<InventoryCount> findBySessionAndProduct(UUID sessionId, UUID productId, UUID variantId) {
        if (variantId == null) {
            return jpaRepository.findBySessionIdAndProductIdAndVariantIdIsNull(sessionId, productId)
                    .map(this::toDomain);
        }
        return jpaRepository.findBySessionIdAndProductIdAndVariantId(sessionId, productId, variantId)
                .map(this::toDomain);
    }

    @Override
    @Transactional
    public InventoryCount upsert(InventoryCount count) {
        Optional<InventoryCountJpaEntity> existing = (count.getVariantId() == null)
                ? jpaRepository.findBySessionIdAndProductIdAndVariantIdIsNull(count.getSessionId(), count.getProductId())
                : jpaRepository.findBySessionIdAndProductIdAndVariantId(count.getSessionId(), count.getProductId(), count.getVariantId());

        if (existing.isPresent()) {
            InventoryCountJpaEntity entity = existing.get();
            entity.setPhysical(count.getPhysical());
            entity.setCountedAt(count.getCountedAt());
            entity.setCountedBy(count.getCountedBy());
            entity.setUpdatedAt(count.getUpdatedAt());
            return toDomain(jpaRepository.save(entity));
        }
        return toDomain(jpaRepository.save(toJpa(count)));
    }

    // ── Mapping (package-private for tests) ────────────────

    InventoryCountJpaEntity toJpa(InventoryCount c) {
        InventoryCountJpaEntity e = new InventoryCountJpaEntity();
        e.setId(c.getId());
        e.setSessionId(c.getSessionId());
        e.setProductId(c.getProductId());
        e.setVariantId(c.getVariantId());
        e.setProductName(c.getProductName());
        e.setVariantLabel(c.getVariantLabel());
        e.setTheoretical(c.getTheoretical());
        e.setPhysical(c.getPhysical());
        e.setCountedAt(c.getCountedAt());
        e.setCountedBy(c.getCountedBy());
        e.setUpdatedAt(c.getUpdatedAt());
        return e;
    }

    InventoryCount toDomain(InventoryCountJpaEntity e) {
        return new InventoryCount(
                e.getId(), e.getSessionId(), e.getProductId(), e.getVariantId(),
                e.getProductName(), e.getVariantLabel(),
                e.getTheoretical(), e.getPhysical(),
                e.getCountedAt(), e.getCountedBy(), e.getUpdatedAt());
    }
}
