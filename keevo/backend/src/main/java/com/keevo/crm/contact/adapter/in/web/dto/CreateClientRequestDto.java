package com.keevo.crm.contact.adapter.in.web.dto;

import com.keevo.crm.api.ClientProfileInput;
import jakarta.validation.constraints.NotBlank;

import java.util.UUID;

/**
 * Request DTO for creating a new client (Story 2.5). Story 20.2 — optional Bref fields (validated by the crm rule set,
 * French messages) and {@code storeId} (OWNER: an active store of the tenant, default = the oldest one; a store
 * member: its own store only). A V1 body {@code {name, phone, email, notes}} keeps working.
 */
public record CreateClientRequestDto(
        @NotBlank(message = "Le nom du client est requis")
        String name,

        @NotBlank(message = "Le téléphone du client est requis")
        String phone,

        String email,
        String notes,
        String clientType,
        String niu,
        String rccm,
        String address,
        String contactName,
        String website,
        String birthDate,
        UUID storeId
) {
    /** Pre-20.2 arity. */
    public CreateClientRequestDto(String name, String phone, String email, String notes) {
        this(name, phone, email, notes, null, null, null, null, null, null, null, null);
    }

    public ClientProfileInput profile() {
        return new ClientProfileInput(clientType, niu, rccm, address, contactName, website, birthDate);
    }
}
