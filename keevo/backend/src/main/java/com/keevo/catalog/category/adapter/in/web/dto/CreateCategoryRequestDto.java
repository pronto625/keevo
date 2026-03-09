package com.keevo.catalog.category.adapter.in.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * CreateCategoryRequestDto — Request DTO for creating custom categories.
 */
public record CreateCategoryRequestDto(
    @NotBlank(message = "Le nom est requis")
    @Size(min = 2, max = 100, message = "Le nom doit contenir entre 2 et 100 caractères")
    String name,
    
    UUID parentId  // null = root category, UUID = subcategory
) {}