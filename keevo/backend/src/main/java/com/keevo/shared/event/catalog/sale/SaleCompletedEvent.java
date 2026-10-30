package com.keevo.shared.event.catalog.sale;

import com.keevo.shared.event.catalog.DomainEvent;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * SaleCompletedEvent — Spring ApplicationEvent published when a sale is completed (direct record, manual validation
 * of a pending sale, automatic validation cascade).
 *
 * <p>GoF Observer: published by RecordSaleService, ValidateSaleService and SaleValidationCascadeService; consumed by
 * AuditEventListener, the business metrics and (Story 14.4) the invoicing module, which creates the sale's invoice.
 *
 * <p>Story 14.4 — the event carries what an invoice needs, so invoicing never reads commerce: the sale client
 * ({@code clientId}, null for an anonymous sale), the payment mode ({@code CASH} | {@code MOBILE_MONEY}) and the
 * typed {@link Line}s (product name, applied VAT-inclusive unit price, quantity). {@code itemsSnapshot} is unchanged
 * (audit). Events stored in the publication registry before 14.4 have none of these three fields: they deserialize
 * with {@code null} values.
 */
public class SaleCompletedEvent implements DomainEvent {

    /** One sold line: the product name, the applied unit price (VAT included, francs) and the quantity. */
    public record Line(String productName, long unitPrice, int quantity) {
    }

    private final UUID saleId;
    private final UUID actorId;
    private final String tenantId;
    private final UUID storeId;
    private final int totalAmount;
    private final int discountAmount; // Story 4.2
    private final String itemsSnapshot; // JSON
    private final Instant occurredAt;
    private final UUID clientId; // Story 14.4 — nullable
    private final String paymentMode; // Story 14.4 — CASH | MOBILE_MONEY
    private final List<Line> lines; // Story 14.4 — null on events published before 14.4

    public SaleCompletedEvent(UUID saleId, UUID actorId, String tenantId,
                              UUID storeId, int totalAmount, int discountAmount,
                              String itemsSnapshot, Instant occurredAt,
                              UUID clientId, String paymentMode, List<Line> lines) {
        this.saleId = saleId;
        this.actorId = actorId;
        this.tenantId = tenantId;
        this.storeId = storeId;
        this.totalAmount = totalAmount;
        this.discountAmount = discountAmount;
        this.itemsSnapshot = itemsSnapshot;
        this.occurredAt = occurredAt;
        this.clientId = clientId;
        this.paymentMode = paymentMode;
        this.lines = lines == null ? null : List.copyOf(lines);
    }

    public UUID getSaleId() { return saleId; }
    public UUID getActorId() { return actorId; }
    public String getTenantId() { return tenantId; }
    public UUID getStoreId() { return storeId; }
    public int getTotalAmount() { return totalAmount; }
    public int getDiscountAmount() { return discountAmount; }
    public String getItemsSnapshot() { return itemsSnapshot; }
    public Instant getOccurredAt() { return occurredAt; }
    public UUID getClientId() { return clientId; }
    public String getPaymentMode() { return paymentMode; }
    public List<Line> getLines() { return lines; }

    // Envelope (Story 12.3) — same values as the getters above, which stay for existing consumers.
    @Override public String tenantId() { return tenantId; }
    @Override public Instant occurredAt() { return occurredAt; }
    @Override public UUID actorId() { return actorId; }
}
