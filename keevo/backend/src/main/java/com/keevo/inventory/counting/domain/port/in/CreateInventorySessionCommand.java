package com.keevo.inventory.counting.domain.port.in;

import com.keevo.inventory.counting.domain.model.InventoryScope;

import java.util.List;
import java.util.UUID;

/**
 * CreateInventorySessionCommand — Pure Java command for creating an inventory session.
 */
public record CreateInventorySessionCommand(
        UUID storeId,
        InventoryScope scope,
        List<UUID> categoryIds,
        UUID actorId
) {}
