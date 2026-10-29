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
 */
public record CreateStoreWithIdInput(UUID storeId, String name, StoreType type, String address, String phone) {
    public CreateStoreWithIdInput {
        if (storeId == null) {
            throw new DomainException(ErrorCode.VALIDATION_ERROR, "Store id is required");
        }
    }
}
