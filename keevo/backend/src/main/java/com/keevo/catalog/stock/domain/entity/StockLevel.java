package com.keevo.catalog.stock.domain.entity;

import java.time.Instant;
import java.util.UUID;

/**
 * StockLevel — current quantity of a product (or variant) in a given store.
 *
 * <p>Denormalized for offline POS performance. Updated by {@link com.keevo.catalog.stock.domain.service.StockOperationService}
 * on every stock-modifying operation.
 *
 * <p>GoF: Value-like entity — equality is based on (productId, storeId) composite key.
 */
public class StockLevel {

    private final UUID id;
    private final UUID productId;
    private final UUID variantId; // nullable — null = simple product, non-null = specific variant
    private final UUID storeId;
    private final int quantity;
    private final Instant updatedAt;
    private final long version;

    public StockLevel(UUID id, UUID productId, UUID variantId, UUID storeId,
                      int quantity, Instant updatedAt, long version) {
        if (productId == null) throw new IllegalArgumentException("productId cannot be null");
        if (storeId == null)   throw new IllegalArgumentException("storeId cannot be null");
        if (quantity < 0)      throw new IllegalArgumentException("quantity cannot be negative");
        this.id         = id;
        this.productId  = productId;
        this.variantId  = variantId;
        this.storeId    = storeId;
        this.quantity   = quantity;
        this.updatedAt  = updatedAt;
        this.version    = version;
    }

    // Backward-compatible constructor (defaults version=0)
    public StockLevel(UUID id, UUID productId, UUID variantId, UUID storeId,
                      int quantity, Instant updatedAt) {
        this(id, productId, variantId, storeId, quantity, updatedAt, 0L);
    }

    public UUID getId()         { return id; }
    public UUID getProductId()  { return productId; }
    public UUID getVariantId()  { return variantId; }
    public UUID getStoreId()    { return storeId; }
    public int  getQuantity()   { return quantity; }
    public Instant getUpdatedAt() { return updatedAt; }
    public long  getVersion()     { return version; }

    /** Returns a new StockLevel with the updated quantity and timestamp. */
    public StockLevel withQuantity(int newQuantity) {
        return new StockLevel(id, productId, variantId, storeId, newQuantity, Instant.now(), version);
    }
}
