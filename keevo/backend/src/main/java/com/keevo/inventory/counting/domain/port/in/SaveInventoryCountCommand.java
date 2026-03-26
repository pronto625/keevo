package com.keevo.inventory.counting.domain.port.in;

import java.util.UUID;

public record SaveInventoryCountCommand(
    UUID sessionId,
    UUID productId,
    UUID variantId,
    String productName,
    String variantLabel,
    int theoretical,
    int physical,
    UUID actorId
) {}
