package com.keevo.commerce.sale.adapter.out.persistence.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "sale_items")
public class SaleItemJpaEntity {

    @Id
    @Column(name = "id", columnDefinition = "UUID")
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sale_id", nullable = false)
    private SaleJpaEntity sale;

    @Column(name = "product_id", nullable = false, columnDefinition = "UUID")
    private UUID productId;

    @Column(name = "variant_id", columnDefinition = "UUID")
    private UUID variantId;

    @Column(name = "product_name", nullable = false, length = 255)
    private String productName;

    @Column(name = "catalogue_unit_price", nullable = false)
    private int catalogueUnitPrice;

    @Column(name = "applied_unit_price", nullable = false)
    private int appliedUnitPrice;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    @Column(name = "subtotal", nullable = false)
    private int subtotal;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public SaleItemJpaEntity() {}

    // Getters & setters
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public SaleJpaEntity getSale() { return sale; }
    public void setSale(SaleJpaEntity sale) { this.sale = sale; }
    public UUID getProductId() { return productId; }
    public void setProductId(UUID productId) { this.productId = productId; }
    public UUID getVariantId() { return variantId; }
    public void setVariantId(UUID variantId) { this.variantId = variantId; }
    public String getProductName() { return productName; }
    public void setProductName(String productName) { this.productName = productName; }
    public int getCatalogueUnitPrice() { return catalogueUnitPrice; }
    public void setCatalogueUnitPrice(int catalogueUnitPrice) { this.catalogueUnitPrice = catalogueUnitPrice; }
    public int getAppliedUnitPrice() { return appliedUnitPrice; }
    public void setAppliedUnitPrice(int appliedUnitPrice) { this.appliedUnitPrice = appliedUnitPrice; }
    public int getQuantity() { return quantity; }
    public void setQuantity(int quantity) { this.quantity = quantity; }
    public int getSubtotal() { return subtotal; }
    public void setSubtotal(int subtotal) { this.subtotal = subtotal; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
