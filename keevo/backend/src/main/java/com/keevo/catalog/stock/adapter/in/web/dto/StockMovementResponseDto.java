package com.keevo.catalog.stock.adapter.in.web.dto;

import java.time.Instant;
import java.util.UUID;

import com.keevo.catalog.stock.domain.entity.StockMovement;

/**
 * StockMovementResponseDto — REST representation of a single movement record.
 * Story 2.3.
 */
public record StockMovementResponseDto(
    UUID id,
    UUID productId,
    UUID variantId,
    UUID storeId,
    String movementType,
    int quantityBefore,
    int quantityChange,
    int quantityAfter,
    UUID actorId,
    String notes,
    Instant occurredAt
) {
    public static StockMovementResponseDto from(StockMovement m) {
        return new StockMovementResponseDto(
            m.getId(),
            m.getProductId(),
            m.getVariantId(),
            m.getStoreId(),
            m.getMovementType().name(),
            m.getQuantityBefore(),
            m.getQuantityChange(),
            m.getQuantityAfter(),
            m.getActorId(),
            m.getNotes(),
            m.getOccurredAt()
        );
    }
}
