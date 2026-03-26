package com.keevo.inventory.counting.domain.port.out;

import com.keevo.inventory.counting.domain.model.InventoryCount;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * InventoryCountRepository — driven port for inventory count persistence.
 */
public interface InventoryCountRepository {

    InventoryCount save(InventoryCount count);

    List<InventoryCount> findBySessionId(UUID sessionId);

    Optional<InventoryCount> findBySessionAndProduct(UUID sessionId, UUID productId, UUID variantId);

    InventoryCount upsert(InventoryCount count);
}
