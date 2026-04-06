package com.keevo.catalog.category.adapter.in.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * RenameCategoryRequestDto — Request payload for renaming a category.
 * POST body: { "name": "Nouveau Nom" }
 */
public record RenameCategoryRequestDto(
    @NotBlank(message = "Le nom ne doit pas être vide")
    @Size(min = 2, max = 100, message = "Le nom doit comporter entre 2 et 100 caractères")
    String name
) {}
