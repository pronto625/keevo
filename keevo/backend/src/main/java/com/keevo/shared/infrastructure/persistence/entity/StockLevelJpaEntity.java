package com.keevo.shared.infrastructure.persistence.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * StockLevelJpaEntity — JPA mapping for stock_levels table (per-tenant schema).
 *
 * <p>Story 2.3 — denormalized stock per product per store for offline POS.
 */
@Entity
@Table(name = "stock_levels")
public class StockLevelJpaEntity {

    @Id
    @Column(name = "id", columnDefinition = "UUID")
    private UUID id;

    @Column(name = "product_id", nullable = false, columnDefinition = "UUID")
    private UUID productId;

    @Column(name = "variant_id", columnDefinition = "UUID")
    private UUID variantId; // nullable

    @Column(name = "store_id", nullable = false, columnDefinition = "UUID")
    private UUID storeId;

    @Column(name = "quantity", nullable = false)
    private Integer quantity = 0;

    @Column(name = "updated_at", nullable = false, columnDefinition = "TIMESTAMPTZ")
    private Instant updatedAt;

    public StockLevelJpaEntity() {}

    public StockLevelJpaEntity(UUID id, UUID productId, UUID variantId, UUID storeId,
                                Integer quantity, Instant updatedAt) {
        this.id        = id;
        this.productId = productId;
        this.variantId = variantId;
        this.storeId   = storeId;
        this.quantity  = quantity != null ? quantity : 0;
        this.updatedAt = updatedAt;
    }

    public UUID getId()             { return id; }
    public void setId(UUID id)      { this.id = id; }

    public UUID getProductId()                  { return productId; }
    public void setProductId(UUID productId)    { this.productId = productId; }

    public UUID getVariantId()                  { return variantId; }
    public void setVariantId(UUID variantId)    { this.variantId = variantId; }

    public UUID getStoreId()                    { return storeId; }
    public void setStoreId(UUID storeId)        { this.storeId = storeId; }

    public Integer getQuantity()                { return quantity; }
    public void setQuantity(Integer quantity)   { this.quantity = quantity; }

    public Instant getUpdatedAt()               { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
