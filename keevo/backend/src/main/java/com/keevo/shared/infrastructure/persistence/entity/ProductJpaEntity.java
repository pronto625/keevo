package com.keevo.shared.infrastructure.persistence.entity;

import com.keevo.catalog.product.domain.entity.ProductStatus;
import jakarta.persistence.*;
import java.util.UUID;
import java.time.Instant;

/**
 * ProductJpaEntity — JPA mapping for products table
 * 
 * Maps to products table with all columns and relationships.
 * Handles tenant isolation via per-tenant schema.
 */
@Entity
@Table(name = "products")
public class ProductJpaEntity {

    @Id
    @Column(name = "id", columnDefinition = "UUID")
    private UUID id;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "sku", nullable = false, length = 20)
    private String sku;

    @Column(name = "category_id", columnDefinition = "UUID")
    private UUID categoryId;

    @Column(name = "price", nullable = false)
    private Integer price = 0;

    @Column(name = "buy_price", nullable = false)
    private Integer buyPrice = 0;

    @Column(name = "stock_quantity", nullable = false)
    private Integer stockQuantity = 0;

    @Column(name = "photo_url", length = 500)
    private String photoUrl;

    @Column(name = "archived", nullable = false)
    private Boolean archived = false;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ProductStatus status = ProductStatus.ACTIVE;

    @Column(name = "created_at", nullable = false, columnDefinition = "TIMESTAMPTZ")
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false, columnDefinition = "TIMESTAMPTZ")
    private Instant updatedAt;

    // Default constructor
    public ProductJpaEntity() {}

    // Constructor for conversions
    public ProductJpaEntity(UUID id, String name, String description, String sku, 
                           UUID categoryId, Integer price, Integer buyPrice, Integer stockQuantity,
                           String photoUrl, Boolean archived, ProductStatus status,
                           Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.name = name;
        this.description = description; 
        this.sku = sku;
        this.categoryId = categoryId;
        this.price = price;
        this.buyPrice = buyPrice;
        this.stockQuantity = stockQuantity;
        this.photoUrl = photoUrl;
        this.archived = archived;
        this.status = status;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    // Getters and Setters
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getSku() { return sku; }
    public void setSku(String sku) { this.sku = sku; }

    public UUID getCategoryId() { return categoryId; }
    public void setCategoryId(UUID categoryId) { this.categoryId = categoryId; }

    public Integer getPrice() { return price; }
    public void setPrice(Integer price) { this.price = price; }

    public Integer getBuyPrice() { return buyPrice; }
    public void setBuyPrice(Integer buyPrice) { this.buyPrice = buyPrice; }

    public Integer getStockQuantity() { return stockQuantity; }
    public void setStockQuantity(Integer stockQuantity) { this.stockQuantity = stockQuantity; }

    public String getPhotoUrl() { return photoUrl; }
    public void setPhotoUrl(String photoUrl) { this.photoUrl = photoUrl; }

    public Boolean getArchived() { return archived; }
    public void setArchived(Boolean archived) { this.archived = archived; }

    public ProductStatus getStatus() { return status; }
    public void setStatus(ProductStatus status) { this.status = status; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        ProductJpaEntity that = (ProductJpaEntity) obj;
        return id != null ? id.equals(that.id) : that.id == null;
    }

    @Override
    public int hashCode() {
        return id != null ? id.hashCode() : 0;
    }

    @Override
    public String toString() {
        return "ProductJpaEntity{" +
                "id=" + id +
                ", name='" + name + '\'' +
                ", sku='" + sku + '\'' +
                ", archived=" + archived +
                ", status=" + status +
                '}';
    }
}