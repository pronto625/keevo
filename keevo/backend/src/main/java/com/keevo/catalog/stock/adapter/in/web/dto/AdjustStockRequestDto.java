package com.keevo.catalog.stock.adapter.in.web.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * AdjustStockRequestDto — body for POST /{productId}/stock/adjust.
 * Story 2.3.
 */
public record AdjustStockRequestDto(
    @NotNull UUID storeId,
    UUID variantId,
    @Min(0) int newQuantity,
    @NotNull String notes
) {}
