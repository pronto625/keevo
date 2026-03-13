package com.keevo.catalog.product.adapter.out.csv;

import com.keevo.catalog.product.adapter.out.csv.validator.*;
import com.keevo.catalog.product.application.dto.CsvColumnMapping;
import com.keevo.catalog.product.application.usecase.CreateProductUseCase;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

/**
 * ProductCsvRowFactory — Factory Method that creates {@link CreateProductUseCase.CreateProductDto}
 * from a raw CSV row map and a column mapping.
 *
 * <p>GoF Pattern: <b>Factory Method</b> — encapsulates row-to-domain transformation.
 * {@code ImportCsvProductsUseCase} stays free of mapping logic and delegates here.
 *
 * <p>GoF dependency: uses <b>Strategy</b> validators ({@link CsvColumnValidator}) for each field.
 */
@Component
public class ProductCsvRowFactory {

    private static final Pattern SKU_PATTERN = Pattern.compile("^KEV-[A-Z0-9]{6}$");
    private static final String SKU_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

    private final RequiredStringValidator  requiredString  = new RequiredStringValidator();
    private final PositiveIntegerValidator positiveInteger = new PositiveIntegerValidator();
    private final OptionalIntegerValidator optionalInteger = new OptionalIntegerValidator();
    private final OptionalStringValidator  optionalString  = new OptionalStringValidator();

    /**
     * Create a {@link CreateProductUseCase.CreateProductDto} from a single CSV row.
     *
     * @param row               header→value map for this row
     * @param mapping           column mapping (required + optional field names)
     * @param lineNumber        1-based data line number for error reporting
     * @param actorId           actor performing the import
     * @param categoryNameToId  tenant category name (lower-cased) → UUID map for category matching
     * @return populated DTO, never null
     * @throws CsvValidationException if a required field is invalid
     */
    public CreateProductUseCase.CreateProductDto fromRow(
            Map<String, String> row,
            CsvColumnMapping mapping,
            int lineNumber,
            UUID actorId,
            Map<String, UUID> categoryNameToId) {

        // Required fields
        String name       = requiredString.validate(  get(row, mapping.nameColumn()),  lineNumber, "nom");
        int    priceVente = positiveInteger.validate( get(row, mapping.priceColumn()), lineNumber, "prix_vente");

        // Optional fields
        int buyPrice      = optionalInteger.validate( get(row, mapping.buyPriceColumn()),      lineNumber, "prix_achat");
        int transportCost = optionalInteger.validate( get(row, mapping.transportCostColumn()), lineNumber, "cout_transport");
        int initialQty    = optionalInteger.validate( get(row, mapping.quantityColumn()),      lineNumber, "quantite_initiale");
        String rawSku     = optionalString.validate(  get(row, mapping.skuColumn()),           lineNumber, "sku");

        // SKU: auto-generate if absent or invalid pattern
        String sku = (rawSku != null && SKU_PATTERN.matcher(rawSku).matches())
                ? rawSku
                : generateSku();

        // Category matching (case-insensitive)
        UUID categoryId = null;
        String rawCategory = optionalString.validate(get(row, mapping.categoryColumn()), lineNumber, "categorie");
        if (rawCategory != null && categoryNameToId != null) {
            categoryId = categoryNameToId.get(rawCategory.toLowerCase());
        }

        // threshold
        int threshold = optionalInteger.validate(get(row, mapping.thresholdColumn()), lineNumber, "seuil_min");

        return new CreateProductUseCase.CreateProductDto(
                name,
                null,           // description — not in CSV
                sku,
                categoryId,
                priceVente,
                buyPrice,
                transportCost,
                initialQty,
                actorId,
                threshold
        );
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /** Safe getter: returns null if columnName is null or not present in row */
    private String get(Map<String, String> row, String columnName) {
        if (columnName == null || row == null) return null;
        return row.get(columnName);
    }

    private String generateSku() {
        StringBuilder sb = new StringBuilder("KEV-");
        for (int i = 0; i < 6; i++) {
            sb.append(SKU_CHARS.charAt(ThreadLocalRandom.current().nextInt(SKU_CHARS.length())));
        }
        return sb.toString();
    }
}
