package com.keevo.reporting.report.domain.port.in;

import java.util.UUID;

/**
 * ResendReportUseCase — Port in for re-triggering WhatsApp delivery.
 * Story 7.2 — Rapport End-of-Day
 */
public interface ResendReportUseCase {

    record ResendReportCommand(UUID reportId, String tenantId) {}

    void resendReport(ResendReportCommand command);
}
