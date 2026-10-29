package com.keevo.commerce.api;

import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;

import java.time.Instant;
import java.util.UUID;

/**
 * Closure of a store's day requested by another module (Story 12.2c — the sync push). The tenant
 * is the {@code tenantSchema} of {@link DayClosureCommandPort#closeDay} (12.2e), which must be the
 * bound tenant.
 *
 * @param automatic true when triggered by the scheduler
 * @param closureId client-generated id (offline device) — null → server generates
 * @param closedAt  when the closure was made on the device — null → now; it decides which day is
 *                  closed
 */
public record CloseDayInput(
        UUID storeId,
        UUID actorId,
        boolean automatic,
        UUID closureId,
        Instant closedAt
) {
    public CloseDayInput {
        if (storeId == null) {
            throw new DomainException(ErrorCode.VALIDATION_ERROR, "Store id is required");
        }
    }
}
