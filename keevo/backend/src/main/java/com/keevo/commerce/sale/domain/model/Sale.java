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
    /** Mobile Money transaction reference typed at checkout — nullable, at most 100 chars. */
    private final String mobileMoneyRef;
    private final int totalAmount; // XAF integer — net: subtotal − discountAmount
    private final int discountAmount; // XAF integer — 0 if no discount (Story 4.2)
    private final SaleStatus status;
    private final Instant occurredAt;
    private final Instant createdAt;
    private final List<SaleItem> items;
    /** Optimistic-lock version (Story v1s-13-10) — 0 for a sale that was never persisted. */
    private final long version;

    public Sale(UUID id, UUID storeId, UUID employeeId, UUID clientId,
                PaymentMode paymentMode, int totalAmount, int discountAmount,
                SaleStatus status, Instant occurredAt, Instant createdAt,
                List<SaleItem> items) {
        this(id, storeId, employeeId, clientId, paymentMode, totalAmount, discountAmount,
                status, occurredAt, createdAt, items, 0L);
    }

    public Sale(UUID id, UUID storeId, UUID employeeId, UUID clientId,
                PaymentMode paymentMode, int totalAmount, int discountAmount,
                SaleStatus status, Instant occurredAt, Instant createdAt,
                List<SaleItem> items, long version) {
        this(id, storeId, employeeId, clientId, paymentMode, totalAmount, discountAmount,
                status, occurredAt, createdAt, items, version, null);
    }

    public Sale(UUID id, UUID storeId, UUID employeeId, UUID clientId,
                PaymentMode paymentMode, int totalAmount, int discountAmount,
                SaleStatus status, Instant occurredAt, Instant createdAt,
                List<SaleItem> items, long version, String mobileMoneyRef) {
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
        this.mobileMoneyRef = normalizeMobileMoneyRef(mobileMoneyRef);
        this.totalAmount = totalAmount;
        this.discountAmount = discountAmount;
        this.status = status;
        this.occurredAt = occurredAt;
        this.createdAt = createdAt;
        this.items = items != null ? List.copyOf(items) : List.of();
        this.version = version;
    }

    public UUID getId() { return id; }
    public UUID getStoreId() { return storeId; }
    public UUID getEmployeeId() { return employeeId; }
    public UUID getClientId() { return clientId; }
    public PaymentMode getPaymentMode() { return paymentMode; }
    public String getMobileMoneyRef() { return mobileMoneyRef; }
    public int getTotalAmount() { return totalAmount; }
    public int getDiscountAmount() { return discountAmount; }
    public SaleStatus getStatus() { return status; }
    public Instant getOccurredAt() { return occurredAt; }
    public Instant getCreatedAt() { return createdAt; }
    public List<SaleItem> getItems() { return items; }
    public long getVersion() { return version; }

    /** Max length of {@code sales.mobile_money_ref}. */
    public static final int MOBILE_MONEY_REF_MAX_LENGTH = 100;

    /**
     * Blank → null, trimmed, cut to the column width: an offline sale already happened, so an
     * over-long reference must not get it refused on every sync attempt.
     */
    static String normalizeMobileMoneyRef(String ref) {
        if (ref == null || ref.isBlank()) return null;
        String trimmed = ref.trim();
        return trimmed.length() > MOBILE_MONEY_REF_MAX_LENGTH
                ? trimmed.substring(0, MOBILE_MONEY_REF_MAX_LENGTH)
                : trimmed;
    }
}
