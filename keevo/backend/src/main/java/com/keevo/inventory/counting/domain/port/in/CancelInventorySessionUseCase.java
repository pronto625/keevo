package com.keevo.inventory.counting.domain.port.in;

import com.keevo.inventory.counting.domain.model.InventorySession;

/**
 * CancelInventorySessionUseCase — Port in for cancelling an inventory session.
 */
public interface CancelInventorySessionUseCase {
    InventorySession execute(CancelInventorySessionCommand command);
}
