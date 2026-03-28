package com.keevo.inventory.counting.domain.port.in;

import com.keevo.inventory.counting.domain.model.ValidateInventoryResult;

/**
 * Port in — validate an inventory session and apply stock adjustments.
 * Story 6.4.
 */
public interface ValidateInventoryUseCase {
    ValidateInventoryResult execute(ValidateInventoryCommand command);
}
