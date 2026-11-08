package com.keevo.invoicing.invoice.adapter.in.rest.dto;

import jakarta.validation.constraints.Size;

/**
 * Story 20.7 — {@code PUT /api/v1/invoices/{id}/layout}: the document's layout. {@code invoiceTemplate} = one of
 * {@code CLASSIC}, {@code MODERN}, {@code MINIMAL}, {@code RECEIPT}, {@code TICKET_CLASSIC}, {@code TICKET_COMPACT},
 * {@code TICKET_DETAILED}, {@code TICKET_MODERN} (Bref codes {@code ticket-classic}… accepted), absent / null = the
 * tenant's default; {@code ticketWidth} = {@code "58"} | {@code "80"} | {@code "A4"}, absent / null = the tenant's.
 */
public record InvoiceLayoutRequestDto(
        @Size(max = 30, message = "Modèle inconnu") String invoiceTemplate,
        @Size(max = 10, message = "Largeur de ticket inconnue : 58, 80 ou A4") String ticketWidth) {
}
