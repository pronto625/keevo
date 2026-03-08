package com.keevo.catalog.product.adapter.in.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * Request DTO for creating a new product
 * 
 * Validation rules:
 * - name: required, 1-100 characters
 * - description: optional, max 500 characters
 * - sku: optional (auto-generated if null)
 * - categoryId: required
 */
public record CreateProductRequestDto(
        @NotBlank(message = "Le nom du produit est requis")
        @Size(max = 100, message = "Le nom du produit ne peut pas dépasser 100 caractères")
        String name,
        
        @Size(max = 500, message = "La description ne peut pas dépasser 500 caractères")
        String description,
        
        @Size(min = 10, max = 10, message = "Le SKU doit respecter le format KEV-XXXXXX")
        String sku,
        
        @NotNull(message = "L'ID de catégorie est requis")
        UUID categoryId
) {}