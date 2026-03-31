package com.keevo.reporting.report.adapter.out.persistence;

import com.keevo.reporting.report.domain.model.DeliveryStatus;
import com.keevo.reporting.report.domain.model.EndOfDayReport;
import com.keevo.reporting.report.domain.model.ReportType;
import com.keevo.reporting.report.domain.port.out.EndOfDayReportRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * EndOfDayReportRepositoryAdapter — Adapter bridging EndOfDayReportRepository port
 * to Spring Data JPA.
 * Story 7.2 — Rapport End-of-Day
 */
@Component
public class EndOfDayReportRepositoryAdapter implements EndOfDayReportRepository {

    private final EndOfDayReportSpringRepository springRepository;

    public EndOfDayReportRepositoryAdapter(EndOfDayReportSpringRepository springRepository) {
        this.springRepository = springRepository;
    }

    @Override
    public void save(EndOfDayReport report) {
        springRepository.save(toEntity(report));
    }

    @Override
    public Optional<EndOfDayReport> findById(UUID id) {
        return springRepository.findById(id).map(this::toDomain);
    }

    @Override
    public Page<EndOfDayReport> findByTypeAndTenant(ReportType type, String tenantId, Pageable pageable) {
        return springRepository
                .findByReportTypeAndTenantIdOrderByReportDateDesc(type.name(), tenantId, pageable)
                .map(this::toDomain);
    }

    @Override
    public List<EndOfDayReport> findPendingRetries(int maxAttempts) {
        return springRepository.findPendingRetries(maxAttempts)
                .stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public boolean allStoresClosedForDate(String tenantId, LocalDate date, int activeStoreCount) {
        int closedCount = springRepository.countDistinctStoresClosed(date, tenantId);
        return closedCount >= activeStoreCount;
    }

    @Override
    public List<EndOfDayReport> findByDateAndTenant(LocalDate date, String tenantId, ReportType type) {
        return springRepository.findByReportDateAndTenantIdAndReportType(date, tenantId, type.name())
                .stream()
                .map(this::toDomain)
                .toList();
    }

    // ── Mapping ───────────────────────────────────────────────────────────────

    private EndOfDayReportJpaEntity toEntity(EndOfDayReport r) {
        return new EndOfDayReportJpaEntity(
                r.getId(), r.getTenantId(), r.getStoreId(), r.getStoreName(),
                r.getReportType().name(), r.getReportDate(), r.getContent(),
                r.getDeliveryStatus().name(), r.getDeliveryAttempts(), r.getLastAttemptAt(),
                r.getTotalRevenue(), r.getTotalSales(), r.isAutomatic(), r.getCreatedAt()
        );
    }

    private EndOfDayReport toDomain(EndOfDayReportJpaEntity e) {
        return new EndOfDayReport(
                e.getId(), e.getTenantId(), e.getStoreId(), e.getStoreName(),
                ReportType.valueOf(e.getReportType()),
                e.getReportDate(), e.getContent(),
                DeliveryStatus.valueOf(e.getDeliveryStatus()),
                e.getDeliveryAttempts(), e.getLastAttemptAt(),
                e.getTotalRevenue(), e.getTotalSales(), e.isAutomatic(), e.getCreatedAt()
        );
    }
}
