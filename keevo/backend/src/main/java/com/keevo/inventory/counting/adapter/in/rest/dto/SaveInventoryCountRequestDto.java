package com.keevo.inventory.counting.adapter.in.rest.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record SaveInventoryCountRequestDto(
        @NotNull UUID productId,
        UUID variantId,
        @NotBlank String productName,
        String variantLabel,
        @NotNull Integer theoretical,
        @NotNull @Min(0) Integer physical
) {}
