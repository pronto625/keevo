package com.keevo.reporting.report.domain.port.in;

import com.keevo.identity.onboarding.domain.model.ReportChannel;
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
            Instant weekStart,         // Monday 00:00:00 WAT as Instant
            Instant weekEnd,           // Sunday 23:59:59 WAT as Instant
            ReportChannel deliveryChannel  // null → uses tenant weeklyReportChannel preference
    ) {}

    EndOfDayReport generateWeeklyReport(WeeklyReportCommand command);
}
