package com.keevo.inventory.counting.adapter.out.persistence;

import com.keevo.inventory.counting.domain.model.InventorySessionStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface JpaInventorySessionRepository extends JpaRepository<InventorySessionJpaEntity, UUID> {

    Optional<InventorySessionJpaEntity> findFirstByStoreIdAndStatusOrderByStartedAtDesc(UUID storeId, InventorySessionStatus status);

    Page<InventorySessionJpaEntity> findByStoreIdOrderByStartedAtDesc(UUID storeId, Pageable pageable);

    Page<InventorySessionJpaEntity> findAllByOrderByStartedAtDesc(Pageable pageable);
}
