package com.keevo.commerce.api;

import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;

import java.time.Instant;
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
        boolean pendingOnly,
        boolean sameDayOnly,
        Instant cancelledAt
) {
    /**
     * Story 22.19 (D18) — {@code cancelledAt}: when the till cancelled (the sync operation's client time, {@code null}
     * = now); the same-day rule compares the sale's day with that day, so a cancel made offline before midnight and
     * synchronised after it is still accepted.
     */
    public CancelSaleInput(UUID saleId, UUID actorId, UUID assignedStoreId, String justification,
                           boolean pendingOnly, boolean sameDayOnly) {
        this(saleId, actorId, assignedStoreId, justification, pendingOnly, sameDayOnly, null);
    }

    /**
     * Without the same-day rule. Story 22.19 (D18) — {@code sameDayOnly}: a COMPLETED sale is cancelled only on the
     * business day it was made (Africa/Douala), else {@code SALE_CANCEL_NOT_SAME_DAY} (the sync channel).
     */
    public CancelSaleInput(UUID saleId, UUID actorId, UUID assignedStoreId, String justification,
                           boolean pendingOnly) {
        this(saleId, actorId, assignedStoreId, justification, pendingOnly, false);
    }

    public CancelSaleInput {
        if (saleId == null) {
            throw new DomainException(ErrorCode.VALIDATION_ERROR, "Sale id is required");
        }
    }
}
