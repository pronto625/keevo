package com.keevo.invoicing.invoice.adapter.in.rest.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/** Body of {@code POST /invoices} and {@code PUT /invoices/{id}} (Story 14.1): the whole draft content. */
public record InvoiceRequestDto(
        @NotNull(message = "Le client est requis")
        @Valid
        InvoiceClientDto client,
        @Size(max = 200, message = "Une facture compte au plus 200 lignes")
        List<@Valid @NotNull(message = "Ligne vide") InvoiceLineRequestDto> lines) {
}
