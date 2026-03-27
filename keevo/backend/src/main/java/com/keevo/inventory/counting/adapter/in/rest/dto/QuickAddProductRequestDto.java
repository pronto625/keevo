package com.keevo.inventory.counting.adapter.in.rest.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * QuickAddProductRequestDto — validated request for quick-adding a product during inventory.
 * Story 6.2.a — 3 required fields + optional sellingPrice.
 */
public record QuickAddProductRequestDto(
    @NotBlank(message = "Le nom du produit est requis")
    @Size(max = 200, message = "Le nom ne doit pas dépasser 200 caractères")
    String name,

    @NotNull(message = "La catégorie est requise")
    UUID categoryId,

    @Min(value = 0, message = "La quantité doit être >= 0")
    int physicalQty,

    @Min(value = 0, message = "Le prix de vente doit être >= 0")
    Integer sellingPrice
) {}
