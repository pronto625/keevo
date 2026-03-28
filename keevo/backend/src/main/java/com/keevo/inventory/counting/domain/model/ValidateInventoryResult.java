package com.keevo.inventory.counting.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * Result of a successful inventory validation.
 * Story 6.4.
 */
public record ValidateInventoryResult(
        UUID sessionId,
        int adjustmentsApplied,
        InventorySessionStatus status,
        Instant completedAt
) {}
