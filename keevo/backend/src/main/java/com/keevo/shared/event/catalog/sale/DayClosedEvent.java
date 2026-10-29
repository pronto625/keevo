package com.keevo.shared.event.catalog.sale;

import com.keevo.shared.event.catalog.DomainEvent;

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
 *   <li>EndOfDayReportListener → active end-of-day report path (AFTER_COMMIT)</li>
 *   <li>DayClosureWhatsAppListener → legacy listener (off by default, AFTER_COMMIT) sending
 *       only to the tenant OWNER's phone</li>
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
 * @param windowStart Start of the reporting window (just after the previous closure, else 00:00 WAT)
 * @param windowEnd   End of the reporting window (= closure time)
 * @param lateFrom      Sales received after this instant are covered even if they happened
 *                      before windowStart (late offline sales), see {@code commerce.api.ClosureWindow}
 * @param receivedUntil Sales received after this instant belong to the next closure
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
        Instant windowEnd,
        Instant lateFrom,
        Instant receivedUntil
) implements DomainEvent {
    /** Plain time window (no late sales). */
    public DayClosedEvent(UUID closureId, UUID storeId, UUID actorId, DayClosureSummary summary,
                          boolean isAutomatic, String tenantId, Instant occurredAt,
                          Instant windowStart, Instant windowEnd) {
        this(closureId, storeId, actorId, summary, isAutomatic, tenantId, occurredAt,
                windowStart, windowEnd, null, null);
    }

}
