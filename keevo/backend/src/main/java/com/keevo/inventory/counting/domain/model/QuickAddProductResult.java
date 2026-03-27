package com.keevo.inventory.counting.domain.model;

import com.keevo.catalog.product.domain.entity.Product;
import com.keevo.catalog.stock.domain.entity.StockLevel;

/**
 * QuickAddProductResult — output of the quick-add facade.
 * Contains the three atomically created entities.
 * Story 6.2.a.
 */
public record QuickAddProductResult(
    Product product,
    StockLevel stockLevel,
    InventoryCount inventoryCount
) {}
