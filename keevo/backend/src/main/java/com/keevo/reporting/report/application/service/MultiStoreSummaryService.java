package com.keevo.reporting.report.application.service;

import com.keevo.messaging.whatsapp.domain.port.out.WhatsAppPort;
import com.keevo.identity.auth.domain.port.out.UserRepository;
import com.keevo.reporting.report.domain.model.EndOfDayReport;
import com.keevo.reporting.report.domain.model.ReportType;
import com.keevo.reporting.report.domain.port.in.GenerateEndOfDayReportUseCase.GenerateReportCommand;
import com.keevo.reporting.report.domain.port.out.EndOfDayReportRepository;
import com.keevo.store.store.domain.port.out.StoreRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/**
 * MultiStoreSummaryService — Generates combined multi-store report when ALL stores for a
 * tenant have been closed for the day.
 * Story 7.2 — Task 6.6 (GREEN).
 */
@Service
public class MultiStoreSummaryService {

    private static final Logger log = LoggerFactory.getLogger(MultiStoreSummaryService.class);
    private static final ZoneId WAT = ZoneId.of("Africa/Lagos");
    private static final String DEFAULT_OWNER_PHONE = "+243000000000";

    private final EndOfDayReportRepository reportRepository;
    private final StoreRepository storeRepository;
    private final WhatsAppPort whatsAppPort;
    private final DailyReportFormatter formatter;
    private final UserRepository userRepository;

    public MultiStoreSummaryService(EndOfDayReportRepository reportRepository,
                                    StoreRepository storeRepository,
                                    WhatsAppPort whatsAppPort,
                                    DailyReportFormatter formatter,
                                    UserRepository userRepository) {
        this.reportRepository = reportRepository;
        this.storeRepository = storeRepository;
        this.whatsAppPort = whatsAppPort;
        this.formatter = formatter;
        this.userRepository = userRepository;
    }

    /**
     * Called after each per-store report is generated.
     * If ALL active stores have been closed for today, generates a DAILY_COMBINED report.
     */
    public void checkAndGenerateCombinedSummary(GenerateReportCommand command) {
        int activeStoreCount = storeRepository.countActive();
        if (activeStoreCount <= 1) {
            // Single store: no combined report needed
            return;
        }

        LocalDate today = command.closedAt() != null
                ? command.closedAt().atZone(WAT).toLocalDate()
                : LocalDate.now(WAT);

        if (!reportRepository.allStoresClosedForDate(command.tenantId(), today, activeStoreCount)) {
            log.debug("Not all stores closed yet for tenant={} date={}", command.tenantId(), today);
            return;
        }

        // Check if DAILY_COMBINED already generated for today
        List<EndOfDayReport> existing = reportRepository.findByDateAndTenant(today, command.tenantId(), ReportType.DAILY_COMBINED);
        if (!existing.isEmpty()) {
            log.debug("DAILY_COMBINED already generated for tenant={} date={}", command.tenantId(), today);
            return;
        }

        log.info("All {} stores closed — generating DAILY_COMBINED for tenant={}", activeStoreCount, command.tenantId());

        // Aggregate from per-store reports
        List<EndOfDayReport> storeReports = reportRepository.findByDateAndTenant(today, command.tenantId(), ReportType.DAILY);
        int totalRevenue = storeReports.stream().mapToInt(EndOfDayReport::getTotalRevenue).sum();
        int totalSales   = storeReports.stream().mapToInt(EndOfDayReport::getTotalSales).sum();

        List<DailyReportFormatter.StoreRevenuePair> stores = storeReports.stream()
                .map(r -> new DailyReportFormatter.StoreRevenuePair(r.getStoreName(), r.getTotalRevenue()))
                .toList();

        String dateStr = today.format(java.time.format.DateTimeFormatter.ofPattern("d MMMM yyyy", java.util.Locale.FRENCH));
        String combinedContent = formatter.formatCombinedSummary(dateStr, stores, totalRevenue, totalSales);

        EndOfDayReport combined = EndOfDayReport.createNew(
                command.tenantId(), command.storeId(), "Toutes boutiques",
                null,  // combined report has no specific actor
                ReportType.DAILY_COMBINED, today,
                combinedContent, totalRevenue, totalSales, command.isAutomatic()
        );
        reportRepository.save(combined);

        // Deliver combined summary
        String ownerPhone = userRepository.findOwnerByTenantSchemaName(command.tenantId())
                .map(u -> u.getPhoneNumber())
                .orElse(DEFAULT_OWNER_PHONE);
        try {
            whatsAppPort.sendReport(ownerPhone, combinedContent);
            combined.markSent();
        } catch (Exception e) {
            log.warn("Multi-store summary WhatsApp delivery failed: {}", e.getMessage());
            combined.markFailed();
        }
        reportRepository.save(combined);
    }
}
