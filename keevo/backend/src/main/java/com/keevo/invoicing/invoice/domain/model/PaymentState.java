package com.keevo.invoicing.invoice.domain.model;

/**
 * Story 20.4 — where the collection of an issued invoice stands (« état de paiement »), computed by
 * {@link PaymentStateCalculator} and stored on {@code invoices.payment_state}. Distinct from the legal
 * {@link InvoiceStatus} (ISSUED / PAID / CANCELLED), which only moves to PAID once the invoice is fully paid.
 */
public enum PaymentState {
    UNPAID("Non payée"),
    PARTIAL("Partiellement payée"),
    PAID("Payée"),
    OVERDUE("En retard");

    private final String label;

    PaymentState(String label) {
        this.label = label;
    }

    /** French label shown to the user. */
    public String label() {
        return label;
    }

    /** The stored value ({@code null}: not stored). */
    public static PaymentState fromStored(String value) {
        return value == null ? null : PaymentState.valueOf(value);
    }
}
