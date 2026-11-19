package com.keevo.invoicing.invoice.adapter.in.rest.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * Body of {@code PUT /invoices/{id}/payments/{paymentId}}: changes an instalment not paid yet. Every field is
 * optional, an absent one is left as it is; {@code note} blank clears the note. Amount in XAF francs.
 */
public record UpdateInvoicePaymentRequestDto(
        @Min(value = 1, message = "Le montant doit être supérieur à 0")
        @Max(value = 1_000_000_000_000L, message = "Le montant est trop élevé")
        Long amount,
        LocalDate dueDate,
        @Size(max = 500, message = "La note ne doit pas dépasser 500 caractères")
        String note) {
}
