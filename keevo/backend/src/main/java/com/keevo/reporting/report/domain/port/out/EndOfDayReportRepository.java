package com.keevo.reporting.report.domain.port.out;

import com.keevo.reporting.report.domain.model.EndOfDayReport;
import com.keevo.reporting.report.domain.model.ReportType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * EndOfDayReportRepository — Driven port for report persistence.
 * Story 7.2 — Rapport End-of-Day
 */
public interface EndOfDayReportRepository {

    void save(EndOfDayReport report);

    Optional<EndOfDayReport> findById(UUID id);

    Page<EndOfDayReport> findByTypeAndTenant(ReportType type, String tenantId, Pageable pageable);

    /**
     * Flexible filter: any combination of storeId, actorId, type. Null = no filter.
     */
    Page<EndOfDayReport> findFiltered(String tenantId, UUID storeId, UUID actorId,
                                      ReportType type, Pageable pageable);

    List<EndOfDayReport> findPendingRetries(int maxAttempts);

    boolean allStoresClosedForDate(String tenantId, LocalDate date, int activeStoreCount);

    List<EndOfDayReport> findByDateAndTenant(LocalDate date, String tenantId, ReportType type);
}
