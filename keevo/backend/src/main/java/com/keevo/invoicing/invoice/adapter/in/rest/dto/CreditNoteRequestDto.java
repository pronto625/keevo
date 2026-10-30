package com.keevo.invoicing.invoice.adapter.in.rest.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

/**
 * Body of {@code POST /invoices/{id}/credit-notes} (Story 14.7): the reason (required, kept on the credit note) and,
 * for a partial credit note, the invoice lines to credit — {@code lines} absent (null) = a full credit note. The
 * quantities are checked by the domain against what remains to credit (422 {@code CREDIT_NOTE_INVALID}).
 */
public record CreditNoteRequestDto(
        @NotBlank(message = "Le motif de l'avoir est requis")
        @Size(max = 300, message = "Le motif de l'avoir ne doit pas dépasser 300 caractères")
        String reason,
        @Size(max = 500, message = "Trop de lignes dans l'avoir")
        List<@Valid @NotNull(message = "Une ligne de l'avoir est vide") Line> lines) {

    /** One invoice line to credit: its position on the invoice and the quantity credited. */
    public record Line(
            @NotNull(message = "Le numéro de la ligne à créditer est requis")
            Integer position,
            @NotNull(message = "La quantité à créditer est requise")
            @Positive(message = "La quantité à créditer doit être supérieure à 0")
            @Digits(integer = 9, fraction = 3, message = "La quantité à créditer accepte au plus 3 décimales")
            BigDecimal quantity) {
    }
}
