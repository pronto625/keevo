package com.keevo.invoicing.invoice.adapter.in.rest.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * The client of an invoice (Story 14.1): free text. Story 14.3: {@code id} = a CRM client chosen in the picker — when
 * {@code crm} is on, a blank {@code name} / {@code phone} is filled from the CRM; the name is required after that
 * resolution (« Le nom du client est obligatoire »), hence no {@code @NotBlank} here. Story 20.5: {@code rccm},
 * {@code email}, {@code contactName}, {@code website} (optional, copied onto the document).
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
        String phone,
        @Size(max = 50, message = "Le RCCM du client ne doit pas dépasser 50 caractères")
        String rccm,
        @Size(max = 255, message = "L'email du client ne doit pas dépasser 255 caractères")
        @Email(message = "L'email du client est invalide")
        String email,
        @Size(max = 120, message = "Le nom du contact ne doit pas dépasser 120 caractères")
        String contactName,
        @Size(max = 200, message = "Le site web du client ne doit pas dépasser 200 caractères")
        String website) {

    /** Pre-20.5 shape: no RCCM, email, contact nor website. */
    public InvoiceClientDto(UUID id, String name, String niu, String address, String phone) {
        this(id, name, niu, address, phone, null, null, null, null);
    }
}
