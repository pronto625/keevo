package com.keevo.crm.api;

import java.util.UUID;

/** Input of {@link ClientCommandPort#createWithId}: a client whose id was assigned on-device. */
public record CreateClientInput(
        UUID id,
        String name,
        String phone,
        String email,
        String notes
) {}
