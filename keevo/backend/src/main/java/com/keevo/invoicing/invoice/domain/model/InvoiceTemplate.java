package com.keevo.invoicing.invoice.domain.model;

import java.util.Locale;
import java.util.Optional;

/**
 * Story 20.7 (fusion Keevo × Bref, FUS-FR21) — the 8 rendering layouts of a document, reproducing Bref's receipt
 * templates ({@code features/invoice/components/invoice-templates/*}): three A4 layouts and five narrow ones (the
 * receipt and the four tickets) printed on the chosen {@link TicketWidth}. {@code null} wherever a template is optional
 * means the Keevo standard layout (the legal A4 invoice of Story 14.2, unchanged).
 *
 * <p>{@link #code()} is Bref's identifier ({@code ticket-classic}…), accepted on input as well as the enum name.
 */
public enum InvoiceTemplate {
    CLASSIC("classic", false, "Classique"),
    MODERN("modern", false, "Moderne"),
    MINIMAL("minimal", false, "Minimal"),
    RECEIPT("receipt", true, "Reçu"),
    TICKET_CLASSIC("ticket-classic", true, "Ticket classique"),
    TICKET_COMPACT("ticket-compact", true, "Ticket compact"),
    TICKET_DETAILED("ticket-detailed", true, "Ticket détaillé"),
    TICKET_MODERN("ticket-modern", true, "Ticket moderne");

    private final String code;
    private final boolean narrow;
    private final String label;

    InvoiceTemplate(String code, boolean narrow, String label) {
        this.code = code;
        this.narrow = narrow;
        this.label = label;
    }

    /** Bref's identifier ({@code classic}, {@code ticket-compact}…). */
    public String code() {
        return code;
    }

    /** French label shown to the user. */
    public String label() {
        return label;
    }

    /** A narrow layout (receipt or ticket): printed on the {@link TicketWidth} paper. */
    public boolean narrow() {
        return narrow;
    }

    /**
     * The template named {@code value}: the enum name or Bref's code, case and {@code -}/{@code _} ignored; blank →
     * empty; anything else → {@link IllegalArgumentException}.
     */
    public static Optional<InvoiceTemplate> parse(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String normalized = value.strip().toUpperCase(Locale.ROOT).replace('-', '_');
        for (InvoiceTemplate t : values()) {
            if (t.name().equals(normalized)) {
                return Optional.of(t);
            }
        }
        throw new IllegalArgumentException("Unknown invoice template");
    }

    /** The stored value back to the enum; {@code null} or unknown → {@code null}. */
    public static InvoiceTemplate fromStored(String name) {
        if (name == null) {
            return null;
        }
        for (InvoiceTemplate t : values()) {
            if (t.name().equals(name)) {
                return t;
            }
        }
        return null;
    }
}
