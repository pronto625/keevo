package com.keevo.inventory.counting.domain.port.in;

import com.keevo.inventory.counting.domain.model.InventorySession;

/**
 * CreateInventorySessionUseCase — Port in for creating a new inventory session.
 */
public interface CreateInventorySessionUseCase {
    InventorySession execute(CreateInventorySessionCommand command);
}
