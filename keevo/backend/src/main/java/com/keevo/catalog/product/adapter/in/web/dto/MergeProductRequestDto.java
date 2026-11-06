package com.keevo.catalog.product.adapter.in.web.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/** Story 20.1 — body of {@code POST /api/v1/products/{id}/merge}: the product that is kept. */
public record MergeProductRequestDto(
        @NotNull(message = "Choisissez le produit à conserver")
        UUID targetId
) {}
