package com.keevo.invoicing.invoice.adapter.in.rest.dto;

import java.util.Optional;

/**
 * Story 20.7 — {@code PUT /api/v1/invoices/{id}/layout}: a partial update of the document's layout. Each field:
 * absent = unchanged; {@code null} = back to the account's default; else {@code invoiceTemplate} = one of
 * {@code CLASSIC}, {@code MODERN}, {@code MINIMAL}, {@code RECEIPT}, {@code TICKET_CLASSIC}, {@code TICKET_COMPACT},
 * {@code TICKET_DETAILED}, {@code TICKET_MODERN} (Bref codes {@code ticket-classic}… accepted), {@code ticketWidth} =
 * {@code "58"} | {@code "80"} | {@code "A4"}. Setters record presence (a JSON {@code null} calls the setter, an absent
 * field does not); unknown fields are ignored.
 */
public final class InvoiceLayoutRequestDto {

    private Optional<String> invoiceTemplate;
    private Optional<String> ticketWidth;

    public void setInvoiceTemplate(String value) {
        this.invoiceTemplate = Optional.ofNullable(value);
    }

    public void setTicketWidth(String value) {
        this.ticketWidth = Optional.ofNullable(value);
    }

    /** {@code null} = absent (unchanged); empty = cleared. */
    public Optional<String> invoiceTemplate() {
        return invoiceTemplate;
    }

    /** {@code null} = absent (unchanged); empty = cleared. */
    public Optional<String> ticketWidth() {
        return ticketWidth;
    }
}
