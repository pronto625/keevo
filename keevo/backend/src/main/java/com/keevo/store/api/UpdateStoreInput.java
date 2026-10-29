package com.keevo.store.api;

import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;

import java.util.UUID;

/**
 * Update of a store requested by another module (Story 12.2d — the sync push). Name rules are
 * enforced by the store use case, as for the REST endpoint.
 */
public record UpdateStoreInput(UUID storeId, String name, String address, String phone, UUID actorId) {
    public UpdateStoreInput {
        if (storeId == null) {
            throw new DomainException(ErrorCode.VALIDATION_ERROR, "Store id is required");
        }
    }
}
