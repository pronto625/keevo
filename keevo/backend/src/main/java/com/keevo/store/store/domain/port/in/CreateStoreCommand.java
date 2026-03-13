package com.keevo.store.store.domain.port.in;

import com.keevo.store.store.domain.model.StoreType;

import java.util.UUID;

/**
 * CreateStoreCommand — Input DTO for CreateStoreUseCase.
 * Story 3.1 — AC1, AC2, AC3.
 */
public record CreateStoreCommand(
        String name,
        StoreType type,
        String address,
        String phone,
        UUID actorId
) {}
