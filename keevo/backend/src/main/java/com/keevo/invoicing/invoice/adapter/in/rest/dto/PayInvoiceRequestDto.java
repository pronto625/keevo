package com.keevo.invoicing.invoice.adapter.in.rest.dto;

import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * Optional body of {@code POST /invoices/{id}/pay}: the payment method as the user names it (« Espèces »…). Story 15.5:
 * {@code paidAt} = the day the client paid ({@code yyyy-MM-dd}, optional — today by default); never in the future nor
 * before the issue date (400 {@code INVOICE_PAYMENT_DATE_INVALID}).
 */
public record PayInvoiceRequestDto(
        @Size(max = 60, message = "Le mode de paiement ne doit pas dépasser 60 caractères")
        String paymentMethod,
        LocalDate paidAt) {
}
