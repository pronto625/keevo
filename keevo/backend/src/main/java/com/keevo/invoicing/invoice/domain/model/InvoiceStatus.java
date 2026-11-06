package com.keevo.invoicing.invoice.domain.model;

/**
 * Lifecycle of an invoice (Story 14.1): {@code DRAFT} (editable, deletable, no number) → {@code ISSUED} (number,
 * issue date and seller snapshot assigned, immutable) → {@code PAID}; {@code ISSUED} → {@code CANCELLED} (reason
 * required, number kept). No other transition; a {@code PAID} invoice is not cancellable: it is corrected or annulled by a credit note
 * (Story 14.7), which leaves its status unchanged. Story 20.3: a quote ({@link InvoiceKind#QUOTE}) stays {@code DRAFT}
 * until it is {@code CONVERTED} into a new invoice or {@code CANCELLED}.
 */
public enum InvoiceStatus {
    DRAFT("Brouillon"),
    ISSUED("Émise"),
    PAID("Payée"),
    CANCELLED("Annulée"),
    /** Story 20.3 — a quote converted into an invoice (its final state; quotes only). */
    CONVERTED("Converti");

    private final String label;

    InvoiceStatus(String label) {
        this.label = label;
    }

    /** French label shown to the user. */
    public String label() {
        return label;
    }

    /** Story 20.3 — the French label for a document of {@code kind} (a quote is masculine: « Annulé »). */
    public String label(InvoiceKind kind) {
        if (kind == InvoiceKind.QUOTE) {
            return this == CANCELLED ? "Annulé" : label;
        }
        return label;
    }
}
