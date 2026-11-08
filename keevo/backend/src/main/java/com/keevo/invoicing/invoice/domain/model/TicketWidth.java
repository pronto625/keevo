package com.keevo.invoicing.invoice.domain.model;

import java.util.Locale;
import java.util.Optional;

/**
 * Story 20.7 — the paper of the narrow layouts ({@link InvoiceTemplate#narrow()}): thermal rolls of 58 and 80 mm, or
 * an A4 sheet (the ticket printed in a 110 mm column centred on the sheet — Bref's widest ticket). Ignored by the A4
 * layouts. {@link #code()} is the API value ({@code "58"}, {@code "80"}, {@code "A4"}).
 */
public enum TicketWidth {
    MM_58("58", 58),
    MM_80("80", 80),
    A4("A4", 110);

    /** Paper of a ticket when neither the document nor the tenant chose one (Bref's default). */
    public static final TicketWidth DEFAULT = MM_80;

    private final String code;
    private final int contentWidthMm;

    TicketWidth(String code, int contentWidthMm) {
        this.code = code;
        this.contentWidthMm = contentWidthMm;
    }

    public String code() {
        return code;
    }

    /** Width of the printed ticket column, in millimetres (the roll width; 110 mm centred on an A4 sheet). */
    public int contentWidthMm() {
        return contentWidthMm;
    }

    /** {@code "58"} / {@code "80"} / {@code "A4"} (also {@code "58mm"}, case ignored); blank → empty; else IAE. */
    public static Optional<TicketWidth> parse(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String v = value.strip().toUpperCase(Locale.ROOT);
        if (v.endsWith("MM")) {
            v = v.substring(0, v.length() - 2).strip();
        }
        for (TicketWidth w : values()) {
            if (w.code.equals(v)) {
                return Optional.of(w);
            }
        }
        throw new IllegalArgumentException("Unknown ticket width");
    }

    /** The stored value ({@code "58"}…) back to the enum; {@code null} or unknown → {@code null}. */
    public static TicketWidth fromCode(String code) {
        for (TicketWidth w : values()) {
            if (w.code.equals(code)) {
                return w;
            }
        }
        return null;
    }
}
