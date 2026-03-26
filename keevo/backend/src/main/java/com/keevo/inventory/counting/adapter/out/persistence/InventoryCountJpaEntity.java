package com.keevo.inventory.counting.adapter.out.persistence;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * InventoryCountJpaEntity — JPA mapping for inventory_counts table (per-tenant schema).
 */
@Entity
@Table(name = "inventory_counts")
public class InventoryCountJpaEntity {

    @Id
    @Column(name = "id", columnDefinition = "UUID")
    private UUID id;

    @Column(name = "session_id", nullable = false, columnDefinition = "UUID")
    private UUID sessionId;

    @Column(name = "product_id", nullable = false, columnDefinition = "UUID")
    private UUID productId;

    @Column(name = "variant_id", columnDefinition = "UUID")
    private UUID variantId;

    @Column(name = "product_name", nullable = false, length = 200)
    private String productName;

    @Column(name = "variant_label", length = 100)
    private String variantLabel;

    @Column(name = "theoretical", nullable = false)
    private int theoretical;

    @Column(name = "physical")
    private Integer physical;

    @Column(name = "counted_at", columnDefinition = "TIMESTAMPTZ")
    private Instant countedAt;

    @Column(name = "counted_by", columnDefinition = "UUID")
    private UUID countedBy;

    @Column(name = "updated_at", nullable = false, columnDefinition = "TIMESTAMPTZ")
    private Instant updatedAt;

    public InventoryCountJpaEntity() {}

    // ── Getters & Setters ─────────────────────────────────

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getSessionId() { return sessionId; }
    public void setSessionId(UUID sessionId) { this.sessionId = sessionId; }

    public UUID getProductId() { return productId; }
    public void setProductId(UUID productId) { this.productId = productId; }

    public UUID getVariantId() { return variantId; }
    public void setVariantId(UUID variantId) { this.variantId = variantId; }

    public String getProductName() { return productName; }
    public void setProductName(String productName) { this.productName = productName; }

    public String getVariantLabel() { return variantLabel; }
    public void setVariantLabel(String variantLabel) { this.variantLabel = variantLabel; }

    public int getTheoretical() { return theoretical; }
    public void setTheoretical(int theoretical) { this.theoretical = theoretical; }

    public Integer getPhysical() { return physical; }
    public void setPhysical(Integer physical) { this.physical = physical; }

    public Instant getCountedAt() { return countedAt; }
    public void setCountedAt(Instant countedAt) { this.countedAt = countedAt; }

    public UUID getCountedBy() { return countedBy; }
    public void setCountedBy(UUID countedBy) { this.countedBy = countedBy; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
