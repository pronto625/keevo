package com.keevo.inventory.counting.domain.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Published when a gap report is generated — triggers audit trail logging.
 * Story 6.3 — Gap Analysis Report.
 */
public record InventoryReportGeneratedEvent(
        UUID sessionId,
        UUID actorId,
        String tenantId,
        Instant occurredAt
) {}
