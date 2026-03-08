package com.keevo.catalog.product.domain.entity;

import java.util.UUID;
import java.time.Instant;
import java.util.regex.Pattern;

/**
 * Product domain entity
 * 
 * Domain validation:
 * - name non-empty
 * - SKU format KEV-[A-Z0-9]{6}
 * - description nullable
 * - archived default false
 * - status default ACTIVE
 */
public class Product {
    private static final Pattern SKU_PATTERN = Pattern.compile("^KEV-[A-Z0-9]{6}$");
    
    private final UUID id;
    private final String name;
    private final String description;
    private final String sku;
    private final UUID categoryId;
    private final Boolean archived;
    private final ProductStatus status;
    private final Instant createdAt;
    private final Instant updatedAt;
    
    public Product(UUID id, String name, String description, String sku, 
                  UUID categoryId, Boolean archived, ProductStatus status, 
                  Instant createdAt, Instant updatedAt) {
        // Validate name
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("Product name cannot be null or empty");
        }
        
        // Validate SKU
        if (sku == null) {
            throw new IllegalArgumentException("Product SKU cannot be null");
        }
        if (!SKU_PATTERN.matcher(sku).matches()) {
            throw new IllegalArgumentException("Product SKU must follow format KEV-[A-Z0-9]{6}");
        }
        
        this.id = id;
        this.name = name.trim();
        this.description = description;
        this.sku = sku;
        this.categoryId = categoryId;
        this.archived = archived != null ? archived : false;
        this.status = status != null ? status : ProductStatus.ACTIVE;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }
    
    // Getters
    public UUID getId() { return id; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public String getSku() { return sku; }
    public UUID getCategoryId() { return categoryId; }
    public Boolean getArchived() { return archived; }
    public ProductStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        Product product = (Product) obj;
        return id != null ? id.equals(product.id) : product.id == null;
    }
    
    @Override
    public int hashCode() {
        return id != null ? id.hashCode() : 0;
    }
    
    @Override
    public String toString() {
        return "Product{" +
                "id=" + id +
                ", name='" + name + '\'' +
                ", sku='" + sku + '\'' +
                ", archived=" + archived +
                ", status=" + status +
                '}';
    }
}