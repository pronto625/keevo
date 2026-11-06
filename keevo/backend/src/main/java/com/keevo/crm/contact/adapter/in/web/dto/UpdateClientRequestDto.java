package com.keevo.crm.contact.adapter.in.web.dto;

import com.keevo.crm.api.ClientProfileInput;

import java.util.UUID;

/**
 * Request DTO for partially updating a client (PATCH — all fields nullable) (Story 2.5). Story 20.2 — Bref fields:
 * absent = unchanged, blank = cleared; {@code storeId} moves the client (OWNER only).
 */
public record UpdateClientRequestDto(
        String name,
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
    public UpdateClientRequestDto(String name, String phone, String email, String notes) {
        this(name, phone, email, notes, null, null, null, null, null, null, null, null);
    }

    public ClientProfileInput profile() {
        return new ClientProfileInput(clientType, niu, rccm, address, contactName, website, birthDate);
    }
}
