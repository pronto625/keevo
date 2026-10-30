package com.keevo.invoicing.invoice.adapter.in.rest.dto;

import jakarta.validation.constraints.Size;

/** {@code POST /api/v1/invoices/{id}/send} — {@code phone} optional: the client's phone when absent (Story 14.2). */
public record SendInvoiceRequestDto(
        @Size(max = 30, message = "Le numéro WhatsApp est trop long") String phone) {
}
