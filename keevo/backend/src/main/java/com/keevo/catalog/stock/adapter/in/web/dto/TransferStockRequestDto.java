package com.keevo.catalog.stock.adapter.in.web.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * TransferStockRequestDto — body for POST /api/v1/stock/transfers.
 * Story 3.3.
 */
public record TransferStockRequestDto(
        @NotNull UUID sourceStoreId,
        @NotNull UUID destinationStoreId,
        @NotNull UUID productId,
        UUID variantId,
        @NotNull @Min(1) Integer quantity,
        String notes
) {}
