package com.keevo.inventory.counting.domain.port.in;

import com.keevo.inventory.counting.domain.model.InventoryCount;

public interface SaveInventoryCountUseCase {
    InventoryCount execute(SaveInventoryCountCommand command);
}
