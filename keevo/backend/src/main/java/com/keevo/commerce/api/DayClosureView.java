package com.keevo.commerce.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Read model of a day closure published by {@code commerce :: api} (Story 12.2c).
 *
 * @param actorId    who triggered the closure (SYSTEM id for an automatic one)
 * @param receivedAt when the server recorded it ({@code closedAt} for rows predating the column)
 * @param automatic  true when triggered by the scheduler
 */
public record DayClosureView(
        UUID id,
        UUID storeId,
        UUID actorId,
        Instant closedAt,
        Instant receivedAt,
        boolean automatic,
        DayClosureSummary summary
) {
}
