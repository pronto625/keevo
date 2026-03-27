package com.keevo.inventory.counting.domain.port.in;

import java.util.UUID;

/**
 * QuickAddProductCommand — input port command for quick-adding a product during inventory.
 * Story 6.2.a — 3 required fields + optional sellingPrice + session context.
 */
public record QuickAddProductCommand(
    UUID sessionId,
    String name,
    UUID categoryId,
    int physicalQty,
    Integer sellingPrice,
    UUID actorId
) {}
