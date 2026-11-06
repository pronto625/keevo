package com.keevo.invoicing.invoice.adapter.in.rest.dto;

import com.keevo.invoicing.invoice.application.usecase.InvoicePaymentUseCase;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Story 20.4 — body of {@code POST /invoices/{id}/payments} and payload of the sync operation
 * {@code ADD_INVOICE_PAYMENT}: a payment received ({@code paidAt}, {@code yyyy-MM-dd}) or a scheduled instalment
 * ({@code dueDate}) — exactly one of the two. {@code paymentId} (optional) = the id chosen by the device: sent again
 * with the same content, nothing is added. Amount in XAF francs.
 */
public record AddInvoicePaymentRequestDto(
        UUID paymentId,
        @NotNull(message = "Le montant est requis")
        @Min(value = 1, message = "Le montant doit être supérieur à 0")
        @Max(value = 1_000_000_000_000L, message = "Le montant est trop élevé")
        Long amount,
        LocalDate paidAt,
        LocalDate dueDate,
        @Size(max = 60, message = "Le mode de paiement ne doit pas dépasser 60 caractères")
        String method,
        @Size(max = 500, message = "La note ne doit pas dépasser 500 caractères")
        String note) {

    /** The use-case command (shared by the REST endpoint and the sync operation). */
    public InvoicePaymentUseCase.NewPayment toCommand() {
        return new InvoicePaymentUseCase.NewPayment(paymentId, amount == null ? 0 : amount, paidAt, dueDate, method,
                note);
    }
}
