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
    UUID actorId,
    UUID countId   // client-generated id (offline device) — null → server generates
) {
    public SaveInventoryCountCommand(UUID sessionId, UUID productId, UUID variantId,
                                     String productName, String variantLabel,
                                     int theoretical, int physical, UUID actorId) {
        this(sessionId, productId, variantId, productName, variantLabel,
                theoretical, physical, actorId, null);
    }
}
