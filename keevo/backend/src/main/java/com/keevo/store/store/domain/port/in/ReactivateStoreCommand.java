package com.keevo.store.store.domain.port.in;

import java.util.UUID;

/**
 * ReactivateStoreCommand — Input DTO for ReactivateStoreUseCase.
 */
public record ReactivateStoreCommand(
        UUID storeId,
        UUID actorId
) {}
