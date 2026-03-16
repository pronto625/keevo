package com.keevo.commerce.sale.adapter.in.rest.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.UUID;

public record SaleItemRequestDto(
        @NotNull UUID productId,
        UUID variantId,
        @NotBlank String productName,
        @Positive int appliedUnitPrice,
        @Positive int quantity
) {}
