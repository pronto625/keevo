package com.keevo.inventory.counting.domain.service;

import com.keevo.inventory.counting.domain.model.InventoryCount;
import com.keevo.inventory.counting.domain.model.InventoryProductRow;
import com.keevo.inventory.counting.domain.model.InventoryScope;

import java.util.List;
import java.util.UUID;

/**
 * InventoryScopeResolver — Strategy interface for resolving products in scope.
 *
 * <p>GoF Strategy: each implementation defines how to resolve products for a scope type.
 * Open/Closed: new scope = new class implementing this interface + register in ScopeResolverRegistry.
 */
public interface InventoryScopeResolver {

    InventoryScope supportedScope();

    List<InventoryProductRow> resolveProducts(
            UUID storeId,
            List<UUID> categoryIds,
            List<InventoryCount> existingCounts);
}
