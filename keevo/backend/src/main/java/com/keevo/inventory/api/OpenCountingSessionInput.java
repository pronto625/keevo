package com.keevo.inventory.api;

import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;

import java.util.List;
import java.util.UUID;

/**
 * Opening of an inventory counting session (Story 12.2b).
 *
 * @param sessionId   client-generated id — a replay of an existing id is an idempotent no-op;
 *                    null → server generates
 * @param categoryIds categories counted by a PARTIAL session, null otherwise
 */
public record OpenCountingSessionInput(
        UUID sessionId,
        UUID storeId,
        CountingScope scope,
        List<UUID> categoryIds,
        UUID actorId
) {
    public OpenCountingSessionInput {
        if (scope == null) {
            throw new DomainException(ErrorCode.VALIDATION_ERROR, "Inventory session scope is required");
        }
    }
}
