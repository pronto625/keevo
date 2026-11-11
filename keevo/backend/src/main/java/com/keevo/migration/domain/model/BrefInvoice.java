package com.keevo.migration.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Story 24.4 — a Bref {@code invoice} row (a sale or a quote) as read from the source, unchanged, with its lines and
 * payments. Field names follow {@code api-bref/src/invoice/.../invoice.entity.ts} ({@code origin/develop} 6faa8b4):
 * amounts {@code decimal(14,2)}, {@code date} civil text {@code YYYY-MM-DD}, {@code shopId}/{@code userId} as text. A
 * quote validated in Bref became a sale in place ({@code validatedAt} set). Columns an older Bref schema lacks read as
 * {@code null}.
 *
 * @param type   {@code sale} | {@code quote}
 * @param status {@code pending} | {@code partial} | {@code overdue} | {@code paid} | {@code cancelled}
 */
public record BrefInvoice(UUID id, String userId, String shopId, String customerName, String customerPhone,
                          String customerEmail, String customerNui, String customerRccm, String customerAddress,
                          String customerContactName, String customerWebsite, BigDecimal subtotal, BigDecimal tax,
                          BigDecimal discount, BigDecimal deliveryFee, BigDecimal total, String status,
                          String paymentMethod, BigDecimal initialPayment, Instant dueDate, String notes, String date,
                          String publicToken, Instant publicTokenExpiresAt, Instant cancelledAt, String cancelledBy,
                          String cancellationReason, String type, Instant validatedAt, Instant createdAt,
                          Instant updatedAt, Instant deletedAt, List<BrefInvoiceItem> items,
                          List<BrefInvoicePayment> payments) {

    public static final String TYPE_QUOTE = "quote";
    public static final String STATUS_PAID = "paid";
    public static final String STATUS_CANCELLED = "cancelled";

    public BrefInvoice {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        subtotal = zeroIfNull(subtotal);
        tax = zeroIfNull(tax);
        discount = zeroIfNull(discount);
        deliveryFee = zeroIfNull(deliveryFee);
        total = zeroIfNull(total);
        initialPayment = zeroIfNull(initialPayment);
        items = items == null ? List.of() : List.copyOf(items);
        payments = payments == null ? List.of() : List.copyOf(payments);
    }

    private static BigDecimal zeroIfNull(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static boolean is(String value, String expected) {
        return value != null && expected.equalsIgnoreCase(value.strip());
    }

    public boolean quote() {
        return is(type, TYPE_QUOTE);
    }

    public boolean deleted() {
        return deletedAt != null;
    }

    public boolean cancelled() {
        return is(status, STATUS_CANCELLED);
    }

    public boolean paid() {
        return is(status, STATUS_PAID);
    }

    /** Canonical form of the invoice row itself (lines and payments have their own records). */
    public String canonical() {
        return Canonical.of("id", id, "userId", userId, "shopId", shopId, "customerName", customerName,
                "customerPhone", customerPhone, "customerEmail", customerEmail, "customerNui", customerNui,
                "customerRccm", customerRccm, "customerAddress", customerAddress,
                "customerContactName", customerContactName, "customerWebsite", customerWebsite,
                "subtotal", Canonical.decimal(subtotal), "tax", Canonical.decimal(tax),
                "discount", Canonical.decimal(discount), "deliveryFee", Canonical.decimal(deliveryFee),
                "total", Canonical.decimal(total), "status", status, "paymentMethod", paymentMethod,
                "initialPayment", Canonical.decimal(initialPayment), "dueDate", dueDate, "notes", notes, "date", date,
                "publicToken", publicToken, "publicTokenExpiresAt", publicTokenExpiresAt, "cancelledAt", cancelledAt,
                "cancelledBy", cancelledBy, "cancellationReason", cancellationReason, "type", type,
                "validatedAt", validatedAt, "createdAt", createdAt, "updatedAt", updatedAt, "deletedAt", deletedAt);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof BrefInvoice other && canonical().equals(other.canonical()) && items.equals(other.items)
                && payments.equals(other.payments);
    }

    @Override
    public int hashCode() {
        return canonical().hashCode();
    }

    /** No personal data in logs and test output. */
    @Override
    public String toString() {
        return "BrefInvoice[" + id + "]";
    }
}
