package com.keevo.catalog.stock.domain.entity;

import java.time.Instant;
import java.util.UUID;

/**
 * StockMovement — immutable audit record of a stock operation.
 *
 * <p>Domain invariant: {@code quantityAfter == quantityBefore + quantityChange}.
 * This invariant is enforced in the constructor to prevent invalid records.
 *
 * <p>GoF: immutable value object — no setters, no mutable state.
 */
public class StockMovement {

    private final UUID id;
    private final UUID productId;
    private final UUID variantId;   // nullable
    private final UUID storeId;
    private final MovementType movementType;
    private final int quantityBefore;
    private final int quantityChange;  // signed: positive=in, negative=out
    private final int quantityAfter;
    private final UUID actorId;
    private final String notes;        // nullable
    private final Instant occurredAt;

    public StockMovement(UUID id, UUID productId, UUID variantId, UUID storeId,
                         MovementType movementType, int quantityBefore, int quantityChange,
                         int quantityAfter, UUID actorId, String notes, Instant occurredAt) {
        if (productId    == null) throw new IllegalArgumentException("productId cannot be null");
        if (storeId      == null) throw new IllegalArgumentException("storeId cannot be null");
        if (movementType == null) throw new IllegalArgumentException("movementType cannot be null");
        if (actorId      == null) throw new IllegalArgumentException("actorId cannot be null");
        // Domain invariant: quantityAfter must equal quantityBefore + quantityChange
        if (quantityAfter != quantityBefore + quantityChange) {
            throw new IllegalArgumentException(
                "Stock quantity invariant violated: quantityAfter(" + quantityAfter +
                ") != quantityBefore(" + quantityBefore + ") + quantityChange(" + quantityChange + ")");
        }
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
        this.occurredAt     = occurredAt != null ? occurredAt : Instant.now();
    }

    public UUID getId()                 { return id; }
    public UUID getProductId()          { return productId; }
    public UUID getVariantId()          { return variantId; }
    public UUID getStoreId()            { return storeId; }
    public MovementType getMovementType() { return movementType; }
    public int getQuantityBefore()      { return quantityBefore; }
    public int getQuantityChange()      { return quantityChange; }
    public int getQuantityAfter()       { return quantityAfter; }
    public UUID getActorId()            { return actorId; }
    public String getNotes()            { return notes; }
    public Instant getOccurredAt()      { return occurredAt; }
}
