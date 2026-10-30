package com.keevo.invoicing.invoice.domain.model;

/**
 * Lifecycle of an invoice (Story 14.1): {@code DRAFT} (editable, deletable, no number) → {@code ISSUED} (number,
 * issue date and seller snapshot assigned, immutable) → {@code PAID}; {@code ISSUED} → {@code CANCELLED} (reason
 * required, number kept). No other transition; a {@code PAID} invoice is not cancellable: it is corrected or annulled by a credit note
 * (Story 14.7), which leaves its status unchanged.
 */
public enum InvoiceStatus {
    DRAFT("Brouillon"),
    ISSUED("Émise"),
    PAID("Payée"),
    CANCELLED("Annulée");

    private final String label;

    InvoiceStatus(String label) {
        this.label = label;
    }

    /** French label shown to the user. */
    public String label() {
        return label;
    }
}
