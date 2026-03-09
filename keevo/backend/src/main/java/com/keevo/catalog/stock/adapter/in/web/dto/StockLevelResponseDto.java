package com.keevo.catalog.stock.adapter.in.web.dto;

import java.time.Instant;
import java.util.UUID;

import com.keevo.catalog.stock.domain.entity.StockLevel;

/**
 * StockLevelResponseDto — REST representation of a stock level per store.
 * Story 2.3.
 */
public record StockLevelResponseDto(
    UUID id,
    UUID productId,
    UUID variantId,
    UUID storeId,
    int quantity,
    int minimumThreshold,
    boolean isLow,
    Instant updatedAt
) {
    /** Map from domain entities (level + product threshold). */
    public static StockLevelResponseDto from(StockLevel level, int minimumThreshold) {
        boolean low = minimumThreshold > 0 && level.getQuantity() <= minimumThreshold;
        return new StockLevelResponseDto(
            level.getId(),
            level.getProductId(),
            level.getVariantId(),
            level.getStoreId(),
            level.getQuantity(),
            minimumThreshold,
            low,
            level.getUpdatedAt()
        );
    }
}
