package com.keevo.catalog.api;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Story 24.3 — catalogue writes of the Bref migration tool: the categories an imported company starts with, and the
 * products imported with their Bref id and dates — untracked ({@code track_stock = false}), automatic SKU, without plan
 * limit (the plan is imported by 24.5), without event, idempotently. The tenant context must be bound to
 * {@code tenantSchema} (the caller opens a transaction in that schema); every method joins the caller's transaction.
 */
public interface CatalogImportPort {

    /**
     * Creates the root categories of {@code templateNames} and the category {@code importedName} that are not there
     * yet (names compared trimmed and case-insensitively, the onboarding seeder's rule; the first spelling kept): one
     * select and one insert. Template categories are standard, the imported-products category is custom (the owner
     * manages it).
     *
     * @return the id of the {@code importedName} category (existing or created) and the number of categories created
     */
    ImportedCategories seedImportCategories(String tenantSchema, List<String> templateNames, String importedName);

    /**
     * Inserts the products whose id is absent, with a fresh unique SKU each, in one statement
     * ({@code ON CONFLICT DO NOTHING}: a row that would break a unique rule is skipped, not failed).
     *
     * @return the ids inserted by this call
     */
    Set<UUID> importProducts(String tenantSchema, List<ImportedProduct> products);

    /** The lower-cased names of every product of the tenant (names an imported product must not take) — one query. */
    Set<String> productNames(String tenantSchema);

    /** Among {@code ids}, those of products that exist — one query. */
    Set<UUID> existingProducts(String tenantSchema, Collection<UUID> ids);

    record ImportedCategories(UUID importedCategoryId, int created) {
    }

    /**
     * A product to import.
     *
     * @param name         trimmed, 1–200 characters, unique case-insensitively in the tenant
     * @param barcode      free reference or barcode (≤ 100), nullable
     * @param productKey   the Bref deduplication key (≤ 250), nullable
     * @param price        selling price, ≥ 0
     * @param mergedIntoId the product it was merged into (imported archived), nullable
     */
    record ImportedProduct(UUID id, String name, String barcode, String productKey, int price, boolean priceManual,
                           boolean archived, UUID mergedIntoId, UUID categoryId, Instant createdAt,
                           Instant updatedAt) {

        public ImportedProduct {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(createdAt, "createdAt");
            Objects.requireNonNull(updatedAt, "updatedAt");
            if (name.isBlank() || name.codePointCount(0, name.length()) > 200) {
                throw new IllegalArgumentException("product name must have 1 to 200 characters");
            }
            if (price < 0) {
                throw new IllegalArgumentException("product price must not be negative");
            }
            if (barcode != null && barcode.codePointCount(0, barcode.length()) > 100) {
                throw new IllegalArgumentException("barcode too long");
            }
            if (productKey != null && productKey.codePointCount(0, productKey.length()) > 250) {
                throw new IllegalArgumentException("product key too long");
            }
        }
    }
}
