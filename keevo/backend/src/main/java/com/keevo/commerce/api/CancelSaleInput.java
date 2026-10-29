package com.keevo.commerce.api;

import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;

import java.util.UUID;

/**
 * Cancellation of a sale (Story 12.2c).
 *
 * @param assignedStoreId store of an EMPLOYEE actor (scope check) — null for an OWNER
 * @param pendingOnly     true for the sync channel: only a PENDING_VALIDATION sale may be
 *                        cancelled; a COMPLETED one is refused with {@code FORBIDDEN}
 */
public record CancelSaleInput(
        UUID saleId,
        UUID actorId,
        UUID assignedStoreId,
        String justification,
        boolean pendingOnly
) {
    public CancelSaleInput {
        if (saleId == null) {
            throw new DomainException(ErrorCode.VALIDATION_ERROR, "Sale id is required");
        }
    }
}
