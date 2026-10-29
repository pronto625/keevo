package com.keevo.crm.api;

import java.util.UUID;

/** Input of {@link ClientCommandPort#update}. */
public record UpdateClientInput(
        UUID clientId,
        String name,
        String phone,
        String email,
        String notes,
        UUID actorId
) {}
