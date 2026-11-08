package com.keevo.invoicing.invoice.adapter.in.rest;

import com.keevo.invoicing.invoice.domain.model.InvoiceTemplate;
import com.keevo.invoicing.invoice.domain.model.TicketWidth;
import com.keevo.shared.infrastructure.web.InvalidRequestParameterException;

import java.util.Optional;

/**
 * Story 20.7 — the one place the API parses a layout ({@code invoiceTemplate}) and a ticket paper
 * ({@code ticketWidth}), from a query parameter or a body field: blank → none, unknown → 400 French.
 */
public final class InvoiceLayoutParams {

    static final String UNKNOWN_TEMPLATE =
            "Modèle inconnu : choisissez Classique, Moderne, Minimal, Reçu ou l'un des 4 tickets";
    static final String UNKNOWN_WIDTH = "Largeur de ticket inconnue : 58, 80 ou A4";

    private InvoiceLayoutParams() {
    }

    /** Blank → {@code null}; unknown → 400. */
    public static InvoiceTemplate template(String value) {
        try {
            return InvoiceTemplate.parse(value).orElse(null);
        } catch (IllegalArgumentException e) {
            throw new InvalidRequestParameterException("template", "unknown invoice template", UNKNOWN_TEMPLATE);
        }
    }

    /** Blank → {@code null}; unknown → 400. */
    public static TicketWidth width(String value) {
        try {
            return TicketWidth.parse(value).orElse(null);
        } catch (IllegalArgumentException e) {
            throw new InvalidRequestParameterException("ticketWidth", "unknown ticket width", UNKNOWN_WIDTH);
        }
    }

    /**
     * A partial-update field: {@code null} (absent) → {@code null} = keep; {@code Optional.empty()} (JSON null) or a
     * blank value → empty = clear; else the parsed value.
     */
    public static Optional<InvoiceTemplate> templateChange(Optional<String> field) {
        return field == null ? null : Optional.ofNullable(template(field.orElse(null)));
    }

    /** Same for the ticket paper. */
    public static Optional<TicketWidth> widthChange(Optional<String> field) {
        return field == null ? null : Optional.ofNullable(width(field.orElse(null)));
    }
}
