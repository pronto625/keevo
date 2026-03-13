package com.keevo.store.store.domain.port.in;

import java.util.UUID;

/**
 * DeactivateStoreCommand — Input DTO for DeactivateStoreUseCase.
 * Story 3.1 — AC5.
 */
public record DeactivateStoreCommand(
        UUID storeId,
        UUID actorId
) {}
