package com.keevo.inventory.counting.domain.model;

import com.keevo.catalog.api.ProductView;
import com.keevo.inventory.stock.domain.entity.StockLevel;

/**
 * QuickAddProductResult — output of the quick-add facade.
 * Contains the three atomically created entities.
 * Story 6.2.a.
 */
public record QuickAddProductResult(
    ProductView product,
    StockLevel stockLevel,
    InventoryCount inventoryCount
) {}
