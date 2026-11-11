package com.keevo.catalog.product.domain.port.out;

import com.keevo.catalog.api.CatalogImportPort.ImportedProduct;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Story 24.3 — set-based persistence of the Bref catalogue import, on an explicit, validated tenant schema: one
 * statement per call whatever the number of rows.
 */
public interface CatalogImportWriter {

    /** A category to create. */
    record NewCategory(UUID id, String name, boolean custom) {
    }

    /** Root categories whose trimmed, lower-cased name is in {@code lowerNames}: lower name → id (oldest first). */
    Map<String, UUID> findRootCategories(String tenantSchema, Collection<String> lowerNames);

    /** Inserts the categories ({@code ON CONFLICT (id) DO NOTHING}); returns the ids inserted. */
    Set<UUID> insertCategories(String tenantSchema, List<NewCategory> categories);

    /** Among {@code skus}, those already used. */
    Set<String> existingSkus(String tenantSchema, Collection<String> skus);

    /** Inserts the products with their SKU (same order); a row breaking a unique rule is skipped. */
    Set<UUID> insertProducts(String tenantSchema, List<ImportedProduct> products, List<String> skus);

    /** Lower-cased names of every product. */
    Set<String> productNames(String tenantSchema);

    /** Among {@code ids}, those of existing products. */
    Set<UUID> existingProducts(String tenantSchema, Collection<UUID> ids);
}
