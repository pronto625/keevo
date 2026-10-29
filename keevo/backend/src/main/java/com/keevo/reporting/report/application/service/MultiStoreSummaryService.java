package com.keevo.reporting.report.application.service;

import com.keevo.shared.capability.notification.WhatsAppSender;
import com.keevo.identity.api.OwnerRecipientResolver;
import com.keevo.reporting.report.domain.model.EndOfDayReport;
import com.keevo.reporting.report.domain.model.ReportType;
import com.keevo.reporting.report.domain.port.in.GenerateEndOfDayReportUseCase.GenerateReportCommand;
import com.keevo.reporting.report.domain.port.out.EndOfDayReportRepository;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.store.api.StoreQueryPort;
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


    private final EndOfDayReportRepository reportRepository;
    private final StoreQueryPort storeQuery;
    private final WhatsAppSender whatsAppSender;
    private final DailyReportFormatter formatter;
    private final OwnerRecipientResolver ownerRecipientResolver;

    public MultiStoreSummaryService(EndOfDayReportRepository reportRepository,
                                    StoreQueryPort storeQuery,
                                    WhatsAppSender whatsAppSender,
                                    DailyReportFormatter formatter,
                                    OwnerRecipientResolver ownerRecipientResolver) {
        this.reportRepository = reportRepository;
        this.storeQuery = storeQuery;
        this.whatsAppSender = whatsAppSender;
        this.formatter = formatter;
        this.ownerRecipientResolver = ownerRecipientResolver;
    }

    /**
     * Called after each per-store report is generated.
     * If ALL active stores have been closed for today, generates a DAILY_COMBINED report.
     */
    public void checkAndGenerateCombinedSummary(GenerateReportCommand command) {
        int activeStoreCount = storeQuery.countActive(TenantContext.getCurrentTenant());
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

        // Aggregate from per-store reports — exclude employee-scoped reports (actorId != null)
        // to avoid double-counting revenue already included in store-level reports.
        List<EndOfDayReport> storeReports = reportRepository.findByDateAndTenant(today, command.tenantId(), ReportType.DAILY)
                .stream()
                .filter(r -> r.getActorId() == null)
                .toList();
        int totalRevenue = storeReports.stream().mapToInt(EndOfDayReport::getTotalRevenue).sum();
        int totalSales   = storeReports.stream().mapToInt(EndOfDayReport::getTotalSales).sum();

        List<DailyReportFormatter.StoreRevenuePair> stores = storeReports.stream()
                .map(r -> new DailyReportFormatter.StoreRevenuePair(r.getStoreName(), r.getTotalRevenue()))
                .toList();

        String dateStr = today.format(java.time.format.DateTimeFormatter.ofPattern("d MMMM yyyy", java.util.Locale.FRENCH));
        String combinedContent = formatter.formatCombinedSummary(dateStr, stores, totalRevenue, totalSales);

        EndOfDayReport combined = EndOfDayReport.createNew(
                command.tenantId(), command.storeId(), "Toutes boutiques",
                null,  // actorId: combined report has no specific actor
                null,  // actorName: N/A for combined
                ReportType.DAILY_COMBINED, today,
                combinedContent, totalRevenue, totalSales, command.isAutomatic()
        );
        reportRepository.save(combined);

        // Deliver combined summary — skip if owner phone not found to avoid sending to an unknown recipient.
        ownerRecipientResolver.resolveOwnerWhatsAppPhone(command.tenantId())
                .ifPresentOrElse(
                        ownerPhone -> {
                            try {
                                whatsAppSender.sendReport(ownerPhone, combinedContent);
                                combined.markSent();
                            } catch (Exception e) {
                                log.warn("Multi-store summary WhatsApp delivery failed: {}", e.getMessage());
                                combined.markFailed();
                            }
                            reportRepository.save(combined);
                        },
                        () -> {
                            log.warn("DAILY_COMBINED delivery skipped — no owner phone found for tenant={}", command.tenantId());
                            combined.markInAppOnly();
                            reportRepository.save(combined);
                        });
    }
}
