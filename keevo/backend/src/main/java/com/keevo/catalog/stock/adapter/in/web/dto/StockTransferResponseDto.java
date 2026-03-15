package com.keevo.catalog.stock.adapter.in.web.dto;

import com.keevo.catalog.stock.domain.model.StockTransfer;

import java.time.Instant;
import java.util.UUID;

/**
 * StockTransferResponseDto — response body for transfer operations.
 * Story 3.3.
 */
public record StockTransferResponseDto(
        UUID id,
        UUID sourceStoreId,
        UUID destinationStoreId,
        UUID productId,
        UUID variantId,
        int quantity,
        UUID actorId,
        Instant occurredAt,
        String status,
        String notes
) {
    public static StockTransferResponseDto from(StockTransfer t) {
        return new StockTransferResponseDto(
                t.getId(),
                t.getSourceStoreId(),
                t.getDestinationStoreId(),
                t.getProductId(),
                t.getVariantId(),
                t.getQuantity(),
                t.getActorId(),
                t.getOccurredAt(),
                t.getStatus() != null ? t.getStatus().name() : null,
                t.getNotes()
        );
    }
}
