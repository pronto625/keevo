package com.keevo.invoicing.invoice.adapter.in.rest.dto;

import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * The client of an invoice (Story 14.1): free text. Story 14.3: {@code id} = a CRM client chosen in the picker — when
 * {@code crm} is on, a blank {@code name} / {@code phone} is filled from the CRM; the name is required after that
 * resolution (« Le nom du client est obligatoire »), hence no {@code @NotBlank} here.
 */
public record InvoiceClientDto(
        UUID id,
        @Size(max = 200, message = "Le nom du client ne doit pas dépasser 200 caractères")
        String name,
        @Size(max = 30, message = "Le NIU du client ne doit pas dépasser 30 caractères")
        String niu,
        @Size(max = 300, message = "L'adresse du client ne doit pas dépasser 300 caractères")
        String address,
        @Size(max = 30, message = "Le téléphone du client ne doit pas dépasser 30 caractères")
        String phone) {
}
