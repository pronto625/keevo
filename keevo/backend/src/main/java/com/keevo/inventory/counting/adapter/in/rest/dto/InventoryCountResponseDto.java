package com.keevo.inventory.counting.adapter.in.rest.dto;

import com.keevo.inventory.counting.domain.model.InventoryCount;

import java.time.Instant;
import java.util.UUID;

public record InventoryCountResponseDto(
        UUID id,
        UUID sessionId,
        UUID productId,
        UUID variantId,
        String productName,
        String variantLabel,
        int theoretical,
        Integer physical,
        Integer ecart,
        Instant countedAt,
        UUID countedBy,
        Instant updatedAt
) {
    public static InventoryCountResponseDto fromDomain(InventoryCount c) {
        Integer ecart = c.isCounted() ? c.getEcart() : null;
        return new InventoryCountResponseDto(
                c.getId(), c.getSessionId(), c.getProductId(), c.getVariantId(),
                c.getProductName(), c.getVariantLabel(),
                c.getTheoretical(), c.getPhysical(), ecart,
                c.getCountedAt(), c.getCountedBy(), c.getUpdatedAt());
    }
}
