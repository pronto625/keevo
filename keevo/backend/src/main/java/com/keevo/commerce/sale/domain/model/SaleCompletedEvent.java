package com.keevo.commerce.sale.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * SaleCompletedEvent — Spring ApplicationEvent published when a sale is recorded.
 *
 * <p>GoF Observer: published by RecordSaleService, consumed by AuditEventListener.
 */
public class SaleCompletedEvent {

    private final UUID saleId;
    private final UUID actorId;
    private final String tenantId;
    private final UUID storeId;
    private final int totalAmount;
    private final int discountAmount; // Story 4.2
    private final String itemsSnapshot; // JSON
    private final Instant occurredAt;

    public SaleCompletedEvent(UUID saleId, UUID actorId, String tenantId,
                              UUID storeId, int totalAmount, int discountAmount,
                              String itemsSnapshot, Instant occurredAt) {
        this.saleId = saleId;
        this.actorId = actorId;
        this.tenantId = tenantId;
        this.storeId = storeId;
        this.totalAmount = totalAmount;
        this.discountAmount = discountAmount;
        this.itemsSnapshot = itemsSnapshot;
        this.occurredAt = occurredAt;
    }

    public UUID getSaleId() { return saleId; }
    public UUID getActorId() { return actorId; }
    public String getTenantId() { return tenantId; }
    public UUID getStoreId() { return storeId; }
    public int getTotalAmount() { return totalAmount; }
    public int getDiscountAmount() { return discountAmount; }
    public String getItemsSnapshot() { return itemsSnapshot; }
    public Instant getOccurredAt() { return occurredAt; }
}
