package com.keevo.inventory.counting.domain.port.in;

import com.keevo.inventory.counting.domain.model.QuickAddProductResult;

/**
 * QuickAddProductUseCase — driving port for quick-adding a product during inventory counting.
 * Story 6.2.a — Facade pattern: atomic creation of Product + StockLevel + InventoryCount.
 */
public interface QuickAddProductUseCase {
    QuickAddProductResult execute(QuickAddProductCommand command);
}
