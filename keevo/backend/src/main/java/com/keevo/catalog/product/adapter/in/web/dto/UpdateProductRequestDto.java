package com.keevo.catalog.product.adapter.in.web.dto;

import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * Request DTO for updating an existing product
 * 
 * All fields are optional - partial updates supported
 */
public record UpdateProductRequestDto(
        @Size(min = 1, max = 100, message = "Le nom du produit doit contenir entre 1 et 100 caractères")
        String name,
        
        @Size(max = 500, message = "La description ne peut pas dépasser 500 caractères")
        String description,
        
        @Size(min = 10, max = 10, message = "Le SKU doit respecter le format KEV-XXXXXX")
        String sku,
        
        UUID categoryId
) {}