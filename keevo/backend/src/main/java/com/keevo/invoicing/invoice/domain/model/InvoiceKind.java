package com.keevo.invoicing.invoice.domain.model;

import java.util.Locale;
import java.util.UUID;

/**
 * Story 20.3 — what an {@code invoices} row is: a legal {@code INVOICE} (numbered {@code FAC-…} at issue) or a
 * {@code QUOTE} (« devis »: never numbered, never counted in the DOCUMENTS quota, converted into a new invoice).
 */
public enum InvoiceKind {
    INVOICE("Facture"),
    QUOTE("Devis");

    private final String label;

    InvoiceKind(String label) {
        this.label = label;
    }

    /** French label shown to the user. */
    public String label() {
        return label;
    }

    /**
     * The display reference of a quote: {@code DV-} + the first 8 hexadecimal characters of its id, upper case
     * (Bref parity: computed, never stored, no counter).
     */
    public static String quoteReference(UUID quoteId) {
        return quoteId == null ? null : "DV-" + quoteId.toString().substring(0, 8).toUpperCase(Locale.ROOT);
    }
}
