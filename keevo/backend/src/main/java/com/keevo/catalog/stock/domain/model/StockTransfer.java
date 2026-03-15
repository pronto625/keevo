package com.keevo.catalog.stock.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * StockTransfer — aggregate root for a stock transfer operation.
 *
 * <p>Immutable after creation. Carries the "transfer header" persisted in
 * stock_transfers table alongside the two stock_movements audit entries.
 *
 * <p>GoF: Command (value object carrying the transfer data).
 * Story 3.3.
 */
public class StockTransfer {

    public enum TransferStatus { IN_TRANSIT, COMPLETED, PENDING_SYNC, CONFLICT }

    private final UUID id;
    private final UUID sourceStoreId;
    private final UUID destinationStoreId;
    private final UUID productId;
    private final UUID variantId;
    private final int quantity;
    private final UUID actorId;
    private final Instant occurredAt;
    private final TransferStatus status;
    private final String notes;

    public StockTransfer(UUID id, UUID sourceStoreId, UUID destinationStoreId,
                         UUID productId, UUID variantId, int quantity,
                         UUID actorId, Instant occurredAt,
                         TransferStatus status, String notes) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("Quantity must be positive, got: " + quantity);
        }
        if (sourceStoreId != null && sourceStoreId.equals(destinationStoreId)) {
            throw new IllegalArgumentException("Source and destination stores must differ");
        }
        this.id                 = id;
        this.sourceStoreId      = sourceStoreId;
        this.destinationStoreId = destinationStoreId;
        this.productId          = productId;
        this.variantId          = variantId;
        this.quantity           = quantity;
        this.actorId            = actorId;
        this.occurredAt         = occurredAt;
        this.status             = status;
        this.notes              = notes;
    }

    public UUID getId()                 { return id; }
    public UUID getSourceStoreId()      { return sourceStoreId; }
    public UUID getDestinationStoreId() { return destinationStoreId; }
    public UUID getProductId()          { return productId; }
    public UUID getVariantId()          { return variantId; }
    public int  getQuantity()           { return quantity; }
    public UUID getActorId()            { return actorId; }
    public Instant getOccurredAt()      { return occurredAt; }
    public TransferStatus getStatus()   { return status; }
    public String getNotes()            { return notes; }

    /** Returns a new immutable instance with the given status. */
    public StockTransfer withStatus(TransferStatus newStatus) {
        return new StockTransfer(id, sourceStoreId, destinationStoreId,
                productId, variantId, quantity, actorId, occurredAt, newStatus, notes);
    }
}
