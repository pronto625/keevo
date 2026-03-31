package com.keevo.reporting.report.domain.port.in;

import com.keevo.reporting.report.domain.model.EndOfDayReport;

import java.time.Instant;
import java.util.UUID;

/**
 * GenerateEndOfDayReportUseCase — Port in for report generation.
 * Story 7.2 — Rapport End-of-Day
 */
public interface GenerateEndOfDayReportUseCase {

    record GenerateReportCommand(
            UUID storeId,
            UUID actorId,
            String tenantId,
            boolean isAutomatic,
            Instant closedAt
    ) {}

    EndOfDayReport generateReport(GenerateReportCommand command);
}
