package com.keevo.invoicing.invoice.adapter.in.rest.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/** The client of an invoice (Story 14.1): free text; {@code id} reserved for the CRM picker (14.3). */
public record InvoiceClientDto(
        UUID id,
        @NotBlank(message = "Le nom du client est requis")
        @Size(max = 200, message = "Le nom du client ne doit pas dépasser 200 caractères")
        String name,
        @Size(max = 30, message = "Le NIU du client ne doit pas dépasser 30 caractères")
        String niu,
        @Size(max = 300, message = "L'adresse du client ne doit pas dépasser 300 caractères")
        String address,
        @Size(max = 30, message = "Le téléphone du client ne doit pas dépasser 30 caractères")
        String phone) {
}
