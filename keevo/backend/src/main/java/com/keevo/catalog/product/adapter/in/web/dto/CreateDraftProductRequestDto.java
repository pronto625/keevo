package com.keevo.catalog.product.adapter.in.web.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * CreateDraftProductRequestDto — HTTP request DTO for {@code POST /api/v1/products/draft}.
 *
 * <p>Story 2.4 — AC5 (employee creates draft, owner creates draft).
 */
public record CreateDraftProductRequestDto(

        @NotBlank(message = "Le nom du produit est obligatoire")
        String name,

        String description,

        UUID categoryId,

        @NotNull(message = "Le prix de vente est obligatoire")
        @Min(value = 0, message = "Le prix de vente ne peut pas être négatif")
        Integer price,

        @Min(value = 0, message = "Le prix d'achat ne peut pas être négatif")
        Integer buyPrice,

        @Min(value = 0, message = "Le coût de transport ne peut pas être négatif")
        Integer transportCost
) {}
