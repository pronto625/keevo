package com.keevo.commerce.sale.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * DayClosedEvent — Domain event published when a day is closed.
 * Pure Java record — no Spring imports.
 * Story 4.4 — Clôture Journalière & Historique des Ventes
 * Story 7.6 — Carries fixed calendar window (windowStart + windowEnd) in WAT.
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
 * @param windowStart Start of the reporting window (00:00:00 WAT of reportDate)
 * @param windowEnd   End of the reporting window (23:59:59.999 WAT of reportDate)
 */
public record DayClosedEvent(
        UUID closureId,
        UUID storeId,
        UUID actorId,
        DayClosureSummary summary,
        boolean isAutomatic,
        String tenantId,
        Instant occurredAt,
        Instant windowStart,
        Instant windowEnd
) {}
