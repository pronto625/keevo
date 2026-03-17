package com.keevo.commerce.sale.domain.model;

import java.util.UUID;

/**
 * SaleItem — line item of a sale. Pure Java, no Spring.
 *
 * <p>Snapshot of product at sale time (name, price) so the record is
 * self-contained even if the product is later modified.
 */
public class SaleItem {

    private final UUID id;
    private final UUID saleId;
    private final UUID productId;
    private final UUID variantId; // nullable
    private final String productName;
    private final int catalogueUnitPrice; // original catalogue price (Story 4.2)
    private final int appliedUnitPrice;
    private final int quantity;

    public SaleItem(UUID id, UUID saleId, UUID productId, UUID variantId,
                    String productName, int catalogueUnitPrice,
                    int appliedUnitPrice, int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("Quantity must be positive, got: " + quantity);
        }
        if (appliedUnitPrice < 0) {
            throw new IllegalArgumentException("Price must be non-negative, got: " + appliedUnitPrice);
        }
        if (catalogueUnitPrice < 0) {
            throw new IllegalArgumentException("Catalogue price must be non-negative, got: " + catalogueUnitPrice);
        }
        this.id = id;
        this.saleId = saleId;
        this.productId = productId;
        this.variantId = variantId;
        this.productName = productName;
        this.catalogueUnitPrice = catalogueUnitPrice;
        this.appliedUnitPrice = appliedUnitPrice;
        this.quantity = quantity;
    }

    public int getSubtotal() {
        return appliedUnitPrice * quantity;
    }

    public UUID getId() { return id; }
    public UUID getSaleId() { return saleId; }
    public UUID getProductId() { return productId; }
    public UUID getVariantId() { return variantId; }
    public String getProductName() { return productName; }
    public int getCatalogueUnitPrice() { return catalogueUnitPrice; }
    public int getAppliedUnitPrice() { return appliedUnitPrice; }
    public int getQuantity() { return quantity; }
}
