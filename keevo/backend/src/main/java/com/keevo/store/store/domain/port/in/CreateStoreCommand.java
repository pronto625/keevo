package com.keevo.store.store.domain.port.in;

import com.keevo.store.api.StoreProfilePatch;
import com.keevo.store.api.StoreType;

import java.util.UUID;

/**
 * CreateStoreCommand — Input DTO for CreateStoreUseCase.
 * Story 3.1 — AC1, AC2, AC3. Story 19.12 — {@code profile}: the Bref fields (raw, validated by the use case).
 */
public record CreateStoreCommand(
        String name,
        StoreType type,
        String address,
        String phone,
        UUID actorId,
        StoreProfilePatch profile
) {
    public CreateStoreCommand {
        if (profile == null) {
            profile = StoreProfilePatch.NONE;
        }
    }

    /** Pre-19.12 arity — no Bref field. */
    public CreateStoreCommand(String name, StoreType type, String address, String phone, UUID actorId) {
        this(name, type, address, phone, actorId, StoreProfilePatch.NONE);
    }
}
