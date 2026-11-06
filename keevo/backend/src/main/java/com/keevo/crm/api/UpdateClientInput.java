package com.keevo.crm.api;

import java.util.UUID;

/**
 * Input of {@link ClientCommandPort#update}.
 *
 * @param profile Story 20.2 — Bref fields, {@code null} = unchanged
 */
public record UpdateClientInput(
        UUID clientId,
        String name,
        String phone,
        String email,
        String notes,
        UUID actorId,
        ClientProfileInput profile
) {
    /** Pre-20.2 arity — Bref fields unchanged. */
    public UpdateClientInput(UUID clientId, String name, String phone, String email, String notes, UUID actorId) {
        this(clientId, name, phone, email, notes, actorId, ClientProfileInput.NONE);
    }
}
