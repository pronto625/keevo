package com.keevo.invoicing.invoice.adapter.in.rest.dto;

import jakarta.validation.constraints.Size;

/** Optional body of {@code POST /invoices/{id}/pay}: the payment method as the user names it (« Espèces »…). */
public record PayInvoiceRequestDto(
        @Size(max = 60, message = "Le mode de paiement ne doit pas dépasser 60 caractères")
        String paymentMethod) {
}
