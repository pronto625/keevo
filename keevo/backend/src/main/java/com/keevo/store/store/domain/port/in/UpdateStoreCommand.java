package com.keevo.store.store.domain.port.in;

import java.util.UUID;

/**
 * UpdateStoreCommand — Input DTO for UpdateStoreUseCase.
 * Note: type is NOT included — store type is immutable after creation (AC4).
 * Story 3.1 — AC4.
 */
public record UpdateStoreCommand(
        UUID storeId,
        String name,
        String address,
        String phone,
        UUID actorId
) {}
