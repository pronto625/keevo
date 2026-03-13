package com.keevo.catalog.product.adapter.in.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * CsvColumnMappingRequestDto — HTTP request DTO that maps CSV column names
 * to domain field names.
 *
 * <p>Sent as JSON alongside the multipart {@code file} in {@code POST /api/v1/products/import}.
 *
 * <p>Only {@code nameColumn} and {@code priceColumn} are mandatory (Jakarta @NotBlank).
 * All other columns are optional — null means the field is absent from the CSV.
 *
 * <p>Story 2.4 — AC2.
 */
public record CsvColumnMappingRequestDto(

        @NotBlank(message = "Le nom de la colonne 'nom' est obligatoire")
        String nameColumn,

        @NotBlank(message = "Le nom de la colonne 'prix_vente' est obligatoire")
        String priceColumn,

        String buyPriceColumn,
        String transportCostColumn,
        String categoryColumn,
        String skuColumn,
        String quantityColumn,
        String thresholdColumn
) {}
