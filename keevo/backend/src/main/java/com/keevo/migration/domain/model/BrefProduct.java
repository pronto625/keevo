package com.keevo.migration.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Story 24.3 — a Bref {@code product} row as read from the source, unchanged. Field names follow
 * {@code api-bref/src/product/.../product.entity.ts} ({@code origin/develop} 6faa8b4): one row per shop and
 * {@code productKey} ({@code bc:<barcode>} or {@code nm:<normalized name>}); a product merged into another keeps its key,
 * is archived and points to its target with {@code mergedIntoId}.
 *
 * @param shopId       the shop as stored (Bref keeps it as text)
 * @param unitPrice    Bref {@code decimal(14,2)}, the suggested price
 * @param mergedIntoId the target product's id as stored (text), {@code null} when not merged
 */
public record BrefProduct(UUID id, String shopId, String name, String nameKey, String productKey, String reference,
                          BigDecimal unitPrice, boolean priceManual, int timesSold, BigDecimal totalQuantity,
                          BigDecimal totalRevenue, Instant lastSoldAt, boolean archived, String mergedIntoId,
                          Instant createdAt, Instant updatedAt) {

    public BrefProduct {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        unitPrice = unitPrice == null ? BigDecimal.ZERO : unitPrice;
        totalQuantity = totalQuantity == null ? BigDecimal.ZERO : totalQuantity;
        totalRevenue = totalRevenue == null ? BigDecimal.ZERO : totalRevenue;
    }

    /** Canonical form of the source row (every field, fixed order) for the record checksum. */
    public String canonical() {
        return Canonical.of("id", id, "shopId", shopId, "name", name, "nameKey", nameKey, "productKey", productKey,
                "reference", reference, "unitPrice", Canonical.decimal(unitPrice), "priceManual", priceManual,
                "timesSold", timesSold, "totalQuantity", Canonical.decimal(totalQuantity),
                "totalRevenue", Canonical.decimal(totalRevenue), "lastSoldAt", lastSoldAt, "isArchived", archived,
                "mergedIntoId", mergedIntoId, "createdAt", createdAt, "updatedAt", updatedAt);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof BrefProduct other && canonical().equals(other.canonical());
    }

    @Override
    public int hashCode() {
        return canonical().hashCode();
    }

    @Override
    public String toString() {
        return "BrefProduct[" + id + "]";
    }
}
