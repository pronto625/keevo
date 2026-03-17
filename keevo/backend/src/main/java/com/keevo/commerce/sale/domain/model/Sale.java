package com.keevo.commerce.sale.domain.model;

import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Sale — aggregate root representing a POS transaction.
 * Pure Java — no Spring.
 */
public class Sale {

    private final UUID id;
    private final UUID storeId;
    private final UUID employeeId;
    private final UUID clientId;  // nullable
    private final PaymentMode paymentMode;
    private final int totalAmount; // XAF integer — net: subtotal − discountAmount
    private final int discountAmount; // XAF integer — 0 if no discount (Story 4.2)
    private final SaleStatus status;
    private final Instant occurredAt;
    private final Instant createdAt;
    private final List<SaleItem> items;

    public Sale(UUID id, UUID storeId, UUID employeeId, UUID clientId,
                PaymentMode paymentMode, int totalAmount, int discountAmount,
                SaleStatus status, Instant occurredAt, Instant createdAt,
                List<SaleItem> items) {
        if (discountAmount < 0) {
            throw new IllegalArgumentException("Discount amount must be non-negative, got: " + discountAmount);
        }
        // Validate discount does not exceed subtotal (must come BEFORE totalAmount check)
        int subtotalSum = items != null ? items.stream().mapToInt(SaleItem::getSubtotal).sum() : 0;
        if (discountAmount > subtotalSum) {
            throw new DomainException(ErrorCode.DISCOUNT_EXCEEDS_SUBTOTAL,
                    "Discount (" + discountAmount + ") exceeds subtotal (" + subtotalSum + ")");
        }
        if (totalAmount < 0) {
            throw new IllegalArgumentException("Total amount must be non-negative, got: " + totalAmount);
        }
        this.id = id;
        this.storeId = storeId;
        this.employeeId = employeeId;
        this.clientId = clientId;
        this.paymentMode = paymentMode;
        this.totalAmount = totalAmount;
        this.discountAmount = discountAmount;
        this.status = status;
        this.occurredAt = occurredAt;
        this.createdAt = createdAt;
        this.items = items != null ? List.copyOf(items) : List.of();
    }

    public UUID getId() { return id; }
    public UUID getStoreId() { return storeId; }
    public UUID getEmployeeId() { return employeeId; }
    public UUID getClientId() { return clientId; }
    public PaymentMode getPaymentMode() { return paymentMode; }
    public int getTotalAmount() { return totalAmount; }
    public int getDiscountAmount() { return discountAmount; }
    public SaleStatus getStatus() { return status; }
    public Instant getOccurredAt() { return occurredAt; }
    public Instant getCreatedAt() { return createdAt; }
    public List<SaleItem> getItems() { return items; }
}
