package com.keevo.crm.api;

import java.time.Instant;
import java.util.UUID;

/** Read model of a client published by the crm API. */
public record ClientView(
        UUID id,
        String name,
        String phone,
        String email,
        String notes,
        boolean archived,
        Instant createdAt,
        Instant updatedAt
) {}
