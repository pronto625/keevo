package com.keevo.inventory.counting.domain.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Domain event published after successful inventory validation.
 * GoF Observer — audit handler reacts without coupling.
 * Story 6.4.
 */
public record InventoryValidatedEvent(
        UUID sessionId,
        UUID actorId,
        int adjustmentCount,
        String tenantId,
        Instant occurredAt
) {}
