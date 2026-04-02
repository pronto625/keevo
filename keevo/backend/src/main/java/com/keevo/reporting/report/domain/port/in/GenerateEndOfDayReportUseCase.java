package com.keevo.reporting.report.domain.port.in;

import com.keevo.identity.onboarding.domain.model.ReportChannel;
import com.keevo.reporting.report.domain.model.EndOfDayReport;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * GenerateEndOfDayReportUseCase — Port in for report generation.
 * Story 7.2 — Rapport End-of-Day
 * Story 7.5 — Added optional deliveryChannel field for per-tenant channel routing.
 */
public interface GenerateEndOfDayReportUseCase {

    record GenerateReportCommand(
            UUID storeId,
            UUID actorId,
            String tenantId,
            boolean isAutomatic,
            Instant closedAt,
            Instant windowStart,
            ReportChannel deliveryChannel   // null → defaults to WHATSAPP (legacy callers)
    ) {
        /** Backward-compatible constructor for callers that pre-date Story 7.5. */
        public GenerateReportCommand(UUID storeId, UUID actorId, String tenantId,
                                     boolean isAutomatic, Instant closedAt, Instant windowStart) {
            this(storeId, actorId, tenantId, isAutomatic, closedAt, windowStart, null);
        }
    }

    EndOfDayReport generateReport(GenerateReportCommand command);
}

