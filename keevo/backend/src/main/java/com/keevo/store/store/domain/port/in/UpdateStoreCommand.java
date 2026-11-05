package com.keevo.store.store.domain.port.in;

import com.keevo.store.api.StoreProfilePatch;

import java.util.UUID;

/**
 * UpdateStoreCommand — Input DTO for UpdateStoreUseCase.
 * Note: type is NOT included — store type is immutable after creation (AC4).
 * Story 3.1 — AC4. Story 19.12 — {@code profile}: the Bref fields, raw (absent / null ⇒ unchanged).
 */
public record UpdateStoreCommand(
        UUID storeId,
        String name,
        String address,
        String phone,
        UUID actorId,
        StoreProfilePatch profile
) {
    public UpdateStoreCommand {
        if (profile == null) {
            profile = StoreProfilePatch.NONE;
        }
    }

    /** Pre-19.12 arity — the Bref fields are left unchanged. */
    public UpdateStoreCommand(UUID storeId, String name, String address, String phone, UUID actorId) {
        this(storeId, name, address, phone, actorId, StoreProfilePatch.NONE);
    }
}
