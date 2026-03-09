package com.keevo.shared.infrastructure.persistence.entity;

import com.keevo.catalog.stock.domain.entity.MovementType;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * StockMovementJpaEntity — JPA mapping for stock_movements table (per-tenant schema).
 *
 * <p>Immutable audit record. Never updated after creation.
 * Story 2.3.
 */
@Entity
@Table(name = "stock_movements")
public class StockMovementJpaEntity {

    @Id
    @Column(name = "id", columnDefinition = "UUID")
    private UUID id;

    @Column(name = "product_id", nullable = false, columnDefinition = "UUID")
    private UUID productId;

    @Column(name = "variant_id", columnDefinition = "UUID")
    private UUID variantId; // nullable

    @Column(name = "store_id", nullable = false, columnDefinition = "UUID")
    private UUID storeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "movement_type", nullable = false, length = 30)
    private MovementType movementType;

    @Column(name = "quantity_before", nullable = false)
    private Integer quantityBefore;

    @Column(name = "quantity_change", nullable = false)
    private Integer quantityChange;

    @Column(name = "quantity_after", nullable = false)
    private Integer quantityAfter;

    @Column(name = "actor_id", nullable = false, columnDefinition = "UUID")
    private UUID actorId;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes; // nullable

    @Column(name = "occurred_at", nullable = false, columnDefinition = "TIMESTAMPTZ")
    private Instant occurredAt;

    public StockMovementJpaEntity() {}

    public StockMovementJpaEntity(UUID id, UUID productId, UUID variantId, UUID storeId,
                                  MovementType movementType, Integer quantityBefore,
                                  Integer quantityChange, Integer quantityAfter,
                                  UUID actorId, String notes, Instant occurredAt) {
        this.id             = id;
        this.productId      = productId;
        this.variantId      = variantId;
        this.storeId        = storeId;
        this.movementType   = movementType;
        this.quantityBefore = quantityBefore;
        this.quantityChange = quantityChange;
        this.quantityAfter  = quantityAfter;
        this.actorId        = actorId;
        this.notes          = notes;
        this.occurredAt     = occurredAt;
    }

    public UUID getId()                       { return id; }
    public UUID getProductId()                { return productId; }
    public UUID getVariantId()                { return variantId; }
    public UUID getStoreId()                  { return storeId; }
    public MovementType getMovementType()     { return movementType; }
    public Integer getQuantityBefore()        { return quantityBefore; }
    public Integer getQuantityChange()        { return quantityChange; }
    public Integer getQuantityAfter()         { return quantityAfter; }
    public UUID getActorId()                  { return actorId; }
    public String getNotes()                  { return notes; }
    public Instant getOccurredAt()            { return occurredAt; }

    // Setters required by JPA
    public void setId(UUID id)                                  { this.id = id; }
    public void setProductId(UUID productId)                    { this.productId = productId; }
    public void setVariantId(UUID variantId)                    { this.variantId = variantId; }
    public void setStoreId(UUID storeId)                        { this.storeId = storeId; }
    public void setMovementType(MovementType movementType)      { this.movementType = movementType; }
    public void setQuantityBefore(Integer quantityBefore)       { this.quantityBefore = quantityBefore; }
    public void setQuantityChange(Integer quantityChange)       { this.quantityChange = quantityChange; }
    public void setQuantityAfter(Integer quantityAfter)         { this.quantityAfter = quantityAfter; }
    public void setActorId(UUID actorId)                        { this.actorId = actorId; }
    public void setNotes(String notes)                          { this.notes = notes; }
    public void setOccurredAt(Instant occurredAt)               { this.occurredAt = occurredAt; }
}
