package com.keevo.inventory.stock.adapter.in.web.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * StockTrackingRequestDto — body of {@code PUT /api/v1/products/{productId}/stock-tracking} (Story 17.12).
 *
 * @param tracked         required — the new setting
 * @param initialQuantity optional starting quantity when enabling (empty / 0 → stock 0, no movement)
 * @param storeId         store of the starting quantity (the owner's active store); required when it is &gt; 0
 */
public record StockTrackingRequestDto(
        @NotNull(message = "Indiquez si le stock doit être suivi") Boolean tracked,
        @Min(value = 0, message = "La quantité de départ doit être un nombre entier positif") Integer initialQuantity,
        UUID storeId
) {}
