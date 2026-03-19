package com.keevo.commerce.sale.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * DayClosedEvent — Domain event published when a day is closed.
 * Pure Java record — no Spring imports.
 * Story 4.4 — Clôture Journalière & Historique des Ventes
 *
 * <p>This event triggers:
 * <ul>
 *   <li>DayClosureWhatsAppListener → sends report to owner (via WhatsAppPort stub)</li>
 *   <li>AuditEventListener → records audit entry</li>
 * </ul>
 *
 * @param closureId   UUID of the DayClosure aggregate
 * @param storeId     Store for which the day was closed
 * @param actorId     Employee/Owner who triggered closure, or SYSTEM_UUID for automatic
 * @param summary     Aggregated sales data for the day
 * @param isAutomatic true if triggered by scheduler, false if manual
 * @param tenantId    Tenant schema name
 * @param occurredAt  Timestamp when the closure occurred
 */
public record DayClosedEvent(
        UUID closureId,
        UUID storeId,
        UUID actorId,
        DayClosureSummary summary,
        boolean isAutomatic,
        String tenantId,
        Instant occurredAt
) {}
