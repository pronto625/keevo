package com.keevo.commerce.sale.adapter.in.rest.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import java.util.UUID;

public record SaleItemRequestDto(
        @NotNull UUID productId,
        UUID variantId,
        @NotBlank String productName,
        @PositiveOrZero int catalogueUnitPrice,    // Story 4.2 — original price
        @PositiveOrZero int appliedUnitPrice,      // changed from @Positive — free sample (AC7)
        @Positive int quantity
) {}
