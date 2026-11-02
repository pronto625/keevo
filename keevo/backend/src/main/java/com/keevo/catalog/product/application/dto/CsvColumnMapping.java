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
        String thresholdColumn,     // optional — seuil_min
        String trackStockColumn     // optional — suivi_stock (Story 17.12); null → the « suivi_stock » header if present
) {
    /** Story 17.12 — default header of the stock-tracking column (French yes/no, blank/absent → « oui »). */
    public static final String DEFAULT_TRACK_STOCK_COLUMN = "suivi_stock";

    /** Without an explicit stock-tracking column (the « suivi_stock » header is still read when present). */
    public CsvColumnMapping(String nameColumn, String priceColumn, String buyPriceColumn,
                            String transportCostColumn, String categoryColumn, String skuColumn,
                            String quantityColumn, String thresholdColumn) {
        this(nameColumn, priceColumn, buyPriceColumn, transportCostColumn, categoryColumn, skuColumn,
                quantityColumn, thresholdColumn, null);
    }

    public CsvColumnMapping {
        if (nameColumn == null || nameColumn.isBlank()) {
            throw new IllegalArgumentException("nameColumn is required and cannot be blank");
        }
        if (priceColumn == null || priceColumn.isBlank()) {
            throw new IllegalArgumentException("priceColumn is required and cannot be blank");
        }
    }
}
