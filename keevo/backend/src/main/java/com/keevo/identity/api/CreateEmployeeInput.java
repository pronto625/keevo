package com.keevo.identity.api;

import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;

import java.util.UUID;

/**
 * Creation of an employee requested by another module (Story 12.2d — the sync push).
 * Phone and names are validated by the identity use case, as for the REST endpoint.
 */
public record CreateEmployeeInput(
        UUID actorId,
        String phoneNumber,
        String firstName,
        String lastName,
        UUID storeId
) {
    public CreateEmployeeInput {
        if (storeId == null) {
            throw new DomainException(ErrorCode.VALIDATION_ERROR, "Store id is required");
        }
    }
}
