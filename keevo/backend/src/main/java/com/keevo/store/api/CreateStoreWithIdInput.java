package com.keevo.store.api;

import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;

import java.util.UUID;

/**
 * Creation of a store with an id chosen on the device (Story 12.2d — the offline CREATE_STORE).
 *
 * @param type    null → {@link StoreType#STORE}
 * @param address nullable
 * @param phone   nullable
 * @param profile the Bref fields (Story 19.12); {@code null} → none (country {@code CM})
 */
public record CreateStoreWithIdInput(UUID storeId, String name, StoreType type, String address, String phone,
                                     StoreProfilePatch profile) {
    public CreateStoreWithIdInput {
        if (storeId == null) {
            throw new DomainException(ErrorCode.VALIDATION_ERROR, "Store id is required");
        }
        if (profile == null) {
            profile = StoreProfilePatch.NONE;
        }
    }

    /** Pre-19.12 arity — no Bref field. */
    public CreateStoreWithIdInput(UUID storeId, String name, StoreType type, String address, String phone) {
        this(storeId, name, type, address, phone, StoreProfilePatch.NONE);
    }
}
