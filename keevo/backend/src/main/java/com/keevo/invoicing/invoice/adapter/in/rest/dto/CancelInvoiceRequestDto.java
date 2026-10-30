package com.keevo.invoicing.invoice.adapter.in.rest.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Body of {@code POST /invoices/{id}/cancel}: the reason is required (kept on the invoice). */
public record CancelInvoiceRequestDto(
        @NotBlank(message = "Le motif d'annulation est requis")
        @Size(max = 500, message = "Le motif ne doit pas dépasser 500 caractères")
        String reason) {
}
