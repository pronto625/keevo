package com.keevo.catalog.stock.adapter.in.web.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * RecordStockEntryRequestDto — body for POST /{productId}/stock/entry.
 * Story 2.3.
 */
public record RecordStockEntryRequestDto(
    @NotNull UUID storeId,
    UUID variantId,              // null for simple products
    @Min(1) int quantity,
    String notes
) {}
