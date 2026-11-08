package com.keevo.invoicing.invoice.adapter.in.rest.dto;

import java.util.Optional;

/**
 * Story 20.7 — {@code PUT /api/v1/invoicing/settings/documents}: a partial update of the account's document defaults.
 * Each field: absent = unchanged; {@code null} = back to the default (Keevo layout, 80 mm, 30 days); else the value
 * ({@code publicLinkDays} 1–365, else 422). The response fields {@code effective*} are not part of the request
 * (ignored). Setters record presence.
 */
public final class DocumentPreferencesRequestDto {

    private Optional<String> invoiceTemplate;
    private Optional<String> ticketWidth;
    private Optional<Integer> publicLinkDays;

    public void setInvoiceTemplate(String value) {
        this.invoiceTemplate = Optional.ofNullable(value);
    }

    public void setTicketWidth(String value) {
        this.ticketWidth = Optional.ofNullable(value);
    }

    public void setPublicLinkDays(Integer value) {
        this.publicLinkDays = Optional.ofNullable(value);
    }

    /** {@code null} = absent (unchanged); empty = cleared. */
    public Optional<String> invoiceTemplate() {
        return invoiceTemplate;
    }

    /** {@code null} = absent (unchanged); empty = cleared. */
    public Optional<String> ticketWidth() {
        return ticketWidth;
    }

    /** {@code null} = absent (unchanged); empty = cleared (30 days). */
    public Optional<Integer> publicLinkDays() {
        return publicLinkDays;
    }
}
