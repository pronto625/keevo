package com.keevo.inventory.stock.adapter.in.web.dto;

import com.keevo.inventory.stock.application.usecase.ChangeStockTrackingUseCase;

import java.util.UUID;

/**
 * StockTrackingResponseDto — answer of {@code PUT /api/v1/products/{productId}/stock-tracking} (Story 17.12).
 *
 * @param initialEntry the STOCK_ENTRY recorded for the starting quantity, {@code null} when none
 */
public record StockTrackingResponseDto(
        UUID productId,
        boolean trackStock,
        StockMovementResponseDto initialEntry
) {
    public static StockTrackingResponseDto from(ChangeStockTrackingUseCase.Result result) {
        return new StockTrackingResponseDto(
                result.product().id(),
                result.product().trackStock(),
                result.initialEntry() == null ? null : StockMovementResponseDto.from(result.initialEntry()));
    }
}
