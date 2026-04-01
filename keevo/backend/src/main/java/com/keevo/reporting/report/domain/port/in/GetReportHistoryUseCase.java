package com.keevo.reporting.report.domain.port.in;

import com.keevo.reporting.report.domain.model.EndOfDayReport;
import com.keevo.reporting.report.domain.model.ReportType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Optional;
import java.util.UUID;

/**
 * GetReportHistoryUseCase — Port in for retrieving report history.
 * Story 7.2 — Rapport End-of-Day
 */
public interface GetReportHistoryUseCase {

    /**
     * @param tenantId  required
     * @param storeId   optional — filter to a specific store
     * @param actorId   optional — filter to reports triggered by a specific user
     * @param type      optional — DAILY / DAILY_COMBINED
     * @param pageable  pagination
     */
    record ReportHistoryQuery(String tenantId, UUID storeId, UUID actorId, ReportType type, Pageable pageable) {}

    Page<EndOfDayReport> getReportHistory(ReportHistoryQuery query);

    Optional<EndOfDayReport> getReportById(UUID reportId, String tenantId);
}
