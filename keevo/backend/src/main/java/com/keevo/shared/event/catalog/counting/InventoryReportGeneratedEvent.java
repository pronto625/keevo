package com.keevo.shared.event.catalog.counting;

import com.keevo.shared.event.catalog.DomainEvent;

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
        Instant occurredAt,
        UUID storeId          // v1s-18-6: store the action happened in (audit store_id)
) implements DomainEvent {}
