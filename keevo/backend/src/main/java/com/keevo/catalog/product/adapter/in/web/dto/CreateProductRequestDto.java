package com.keevo.catalog.product.adapter.in.web.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * Request DTO for creating a new product
 * 
 * Validation rules:
 * - name: required, 1-100 characters
 * - description: optional, max 500 characters
 * - sku: optional (auto-generated if null)
 * - categoryId: optional
 * - price: optional, >= 0
 */
public record CreateProductRequestDto(
        @NotBlank(message = "Le nom du produit est requis")
        @Size(max = 100, message = "Le nom du produit ne peut pas dépasser 100 caractères")
        String name,
        
        @Size(max = 500, message = "La description ne peut pas dépasser 500 caractères")
        String description,
        
        @Size(min = 10, max = 10, message = "Le SKU doit respecter le format KEV-XXXXXX")
        String sku,
        
        UUID categoryId,
        
        @Min(value = 0, message = "Le prix ne peut pas être négatif")
        Integer price,
        
        @Min(value = 0, message = "Le prix d'achat ne peut pas être négatif")
        Integer buyPrice,
        
        @Min(value = 0, message = "Le coût de transport ne peut pas être négatif")
        Integer transportCost,
        
        @Min(value = 0, message = "Le stock ne peut pas être négatif")
        Integer stockQuantity,

        /// Optional status — defaults to ACTIVE. Story 4.3: POS can create DRAFT products.
        String status
) {}