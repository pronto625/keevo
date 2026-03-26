package com.keevo.inventory.counting.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface JpaInventoryCountRepository extends JpaRepository<InventoryCountJpaEntity, UUID> {

    List<InventoryCountJpaEntity> findBySessionId(UUID sessionId);

    Optional<InventoryCountJpaEntity> findBySessionIdAndProductIdAndVariantIdIsNull(UUID sessionId, UUID productId);

    Optional<InventoryCountJpaEntity> findBySessionIdAndProductIdAndVariantId(UUID sessionId, UUID productId, UUID variantId);
}
