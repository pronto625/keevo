package com.keevo.shared.infrastructure.persistence.entity;

import com.keevo.catalog.stock.domain.model.StockTransfer.TransferStatus;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * StockTransferJpaEntity — JPA mapping for stock_transfers table (per-tenant schema).
 *
 * <p>Immutable after creation. Carries the transfer header alongside the two
 * stock_movements audit entries written by StockOperationService.
 *
 * Story 3.3.
 */
@Entity
@Table(name = "stock_transfers")
public class StockTransferJpaEntity {

    @Id
    @Column(name = "id", columnDefinition = "UUID")
    private UUID id;

    @Column(name = "source_store_id", nullable = false, columnDefinition = "UUID")
    private UUID sourceStoreId;

    @Column(name = "destination_store_id", nullable = false, columnDefinition = "UUID")
    private UUID destinationStoreId;

    @Column(name = "product_id", nullable = false, columnDefinition = "UUID")
    private UUID productId;

    @Column(name = "variant_id", columnDefinition = "UUID")
    private UUID variantId;

    @Column(name = "quantity", nullable = false)
    private Integer quantity;

    @Column(name = "actor_id", nullable = false, columnDefinition = "UUID")
    private UUID actorId;

    @Column(name = "occurred_at", nullable = false, columnDefinition = "TIMESTAMPTZ")
    private Instant occurredAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private TransferStatus status;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    public StockTransferJpaEntity() {}

    public StockTransferJpaEntity(UUID id, UUID sourceStoreId, UUID destinationStoreId,
                                  UUID productId, UUID variantId, Integer quantity,
                                  UUID actorId, Instant occurredAt,
                                  TransferStatus status, String notes) {
        this.id                   = id;
        this.sourceStoreId        = sourceStoreId;
        this.destinationStoreId   = destinationStoreId;
        this.productId            = productId;
        this.variantId            = variantId;
        this.quantity             = quantity;
        this.actorId              = actorId;
        this.occurredAt           = occurredAt;
        this.status               = status;
        this.notes                = notes;
    }

    public UUID getId()                   { return id; }
    public UUID getSourceStoreId()        { return sourceStoreId; }
    public UUID getDestinationStoreId()   { return destinationStoreId; }
    public UUID getProductId()            { return productId; }
    public UUID getVariantId()            { return variantId; }
    public Integer getQuantity()          { return quantity; }
    public UUID getActorId()              { return actorId; }
    public Instant getOccurredAt()        { return occurredAt; }
    public TransferStatus getStatus()     { return status; }
    public String getNotes()              { return notes; }

    public void setId(UUID id)                                  { this.id = id; }
    public void setSourceStoreId(UUID sourceStoreId)            { this.sourceStoreId = sourceStoreId; }
    public void setDestinationStoreId(UUID destinationStoreId)  { this.destinationStoreId = destinationStoreId; }
    public void setProductId(UUID productId)                    { this.productId = productId; }
    public void setVariantId(UUID variantId)                    { this.variantId = variantId; }
    public void setQuantity(Integer quantity)                   { this.quantity = quantity; }
    public void setActorId(UUID actorId)                        { this.actorId = actorId; }
    public void setOccurredAt(Instant occurredAt)               { this.occurredAt = occurredAt; }
    public void setStatus(TransferStatus status)                { this.status = status; }
    public void setNotes(String notes)                          { this.notes = notes; }
}
