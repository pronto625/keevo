package com.keevo.reporting.report.domain.port.in;

import com.keevo.identity.api.ReportChannel;
import com.keevo.reporting.report.domain.model.EndOfDayReport;

import java.time.Instant;
import java.util.UUID;

/**
 * GenerateWeeklyReportUseCase — Port in for weekly report generation.
 * Story 7.3 — Rapport Hebdomadaire Automatique.
 *
 * <p>Implemented by WeeklyReportGenerator (application layer).
 * Invoked by WeeklyReportScheduler (automatic) and ReportController#triggerWeekly (manual).
 */
public interface GenerateWeeklyReportUseCase {

    record WeeklyReportCommand(
            UUID storeId,
            String tenantId,
            boolean isAutomatic,
            Instant weekStart,         // first sale time covered (see WeeklyReportWindowResolver)
            Instant weekEnd,           // last sale time covered (generation time or Sunday 23:59:59.999999 WAT)
            ReportChannel deliveryChannel,  // null → uses tenant weeklyReportChannel preference
            Instant lateFrom,          // sales received after this are covered even if made before weekStart; null → none
            Instant receivedUntil      // sales received after this belong to the next report; null → no limit
    ) {
        /** Plain time window (no late sales, no arrival limit). */
        public WeeklyReportCommand(UUID storeId, String tenantId, boolean isAutomatic,
                                   Instant weekStart, Instant weekEnd, ReportChannel deliveryChannel) {
            this(storeId, tenantId, isAutomatic, weekStart, weekEnd, deliveryChannel, null, null);
        }
    }

    EndOfDayReport generateWeeklyReport(WeeklyReportCommand command);
}
