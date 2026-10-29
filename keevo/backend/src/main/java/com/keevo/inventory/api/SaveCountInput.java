package com.keevo.inventory.api;

import java.util.UUID;

/**
 * A counted product line of an inventory session (Story 12.2b). Upserted by
 * (session, product, variant).
 *
 * @param countId client-generated id (offline device) — null → server generates
 */
public record SaveCountInput(
        UUID sessionId,
        UUID productId,
        UUID variantId,
        String productName,
        String variantLabel,
        int theoretical,
        int physical,
        UUID actorId,
        UUID countId
) {}
