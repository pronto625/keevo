package com.keevo.inventory.counting.domain.port.out;

import com.keevo.inventory.counting.domain.model.InventorySession;

import java.util.Optional;
import java.util.UUID;

/**
 * InventorySessionRepository — Driven port for inventory session persistence.
 *
 * <p>Pure Java — no framework dependencies.
 */
public interface InventorySessionRepository {

    InventorySession save(InventorySession session);

    Optional<InventorySession> findById(UUID id);

    Optional<InventorySession> findActiveByStoreId(UUID storeId);

    java.util.List<InventorySession> findByStoreId(UUID storeId, int page, int size);

    java.util.List<InventorySession> findAll(int page, int size);
}
