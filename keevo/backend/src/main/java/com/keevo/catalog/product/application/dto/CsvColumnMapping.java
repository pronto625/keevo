package com.keevo.catalog.product.application.dto;

/**
 * CsvColumnMapping — Maps CSV column names to Keevo product fields.
 *
 * <p>Domain invariants: nameColumn and priceColumn are required (non-null, non-blank).
 * All other columns are optional (may be null → field ignored during import).
 *
 * <p>GoF: Used as input to the Factory Method {@code ProductCsvRowFactory.fromRow()}.
 */
public record CsvColumnMapping(
        String nameColumn,          // required — nom du produit
        String priceColumn,         // required — prix de vente
        String buyPriceColumn,      // optional — prix d'achat
        String transportCostColumn, // optional — coût de transport
        String categoryColumn,      // optional — catégorie (matched by name)
        String skuColumn,           // optional — SKU (auto-generated if blank/invalid)
        String quantityColumn,      // optional — quantite_initiale
        String thresholdColumn      // optional — seuil_min
) {
    public CsvColumnMapping {
        if (nameColumn == null || nameColumn.isBlank()) {
            throw new IllegalArgumentException("nameColumn is required and cannot be blank");
        }
        if (priceColumn == null || priceColumn.isBlank()) {
            throw new IllegalArgumentException("priceColumn is required and cannot be blank");
        }
    }
}
