package com.keevo.migration.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Story 24.4 — a Bref {@code invoice_payment} row (an instalment, paid or not), unchanged
 * ({@code invoice-payment.entity.ts}): {@code dueDate} and {@code paidDate} are civil dates kept as text. The acompte of
 * an invoice is not a row: it is {@link BrefInvoice#initialPayment()}.
 */
public record BrefInvoicePayment(UUID id, UUID invoiceId, BigDecimal amount, String dueDate, boolean paid,
                                 String paidDate, String notes, Instant createdAt, Instant updatedAt) {

    public BrefInvoicePayment {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(invoiceId, "invoiceId");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        amount = amount == null ? BigDecimal.ZERO : amount;
    }

    public String canonical() {
        return Canonical.of("id", id, "invoiceId", invoiceId, "amount", Canonical.decimal(amount), "dueDate", dueDate,
                "isPaid", paid, "paidDate", paidDate, "notes", notes, "createdAt", createdAt, "updatedAt", updatedAt);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof BrefInvoicePayment other && canonical().equals(other.canonical());
    }

    @Override
    public int hashCode() {
        return canonical().hashCode();
    }

    @Override
    public String toString() {
        return "BrefInvoicePayment[" + id + "]";
    }
}
