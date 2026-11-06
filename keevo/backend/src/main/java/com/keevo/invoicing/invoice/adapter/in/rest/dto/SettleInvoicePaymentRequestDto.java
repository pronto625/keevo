package com.keevo.invoicing.invoice.adapter.in.rest.dto;

import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * Story 20.4 — optional body of {@code POST /invoices/{id}/payments/{paymentId}/settle} and payload of the sync
 * operation {@code SETTLE_INVOICE_PAYMENT}: {@code paidAt} = the day the instalment was paid ({@code yyyy-MM-dd},
 * today by default); {@code method} replaces the instalment's when given.
 */
public record SettleInvoicePaymentRequestDto(
        LocalDate paidAt,
        @Size(max = 60, message = "Le mode de paiement ne doit pas dépasser 60 caractères")
        String method) {
}
