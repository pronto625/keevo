package com.keevo.reporting.report.application.service;

import com.keevo.messaging.whatsapp.domain.port.out.WhatsAppPort;
import com.keevo.identity.onboarding.domain.model.ReportChannel;
import com.keevo.reporting.report.domain.model.EndOfDayReport;
import com.keevo.reporting.report.domain.model.EndOfDayReportData;
import com.keevo.reporting.report.domain.model.ReportType;
import com.keevo.reporting.report.domain.port.in.GenerateEndOfDayReportUseCase;
import com.keevo.reporting.report.domain.port.out.EndOfDayReportRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

/**
 * AbstractReportGenerator — GoF Template Method pattern.
 * Story 7.2 — defines the report generation algorithm skeleton.
 *
 * <p>Skeleton: collectData → formatContent → persistReport → deliverReport → checkMultiStore.
 * Concrete subclasses (DailyReportGenerator, WeeklyReportGenerator) override collectData + formatContent.
 */
public abstract class AbstractReportGenerator {

    private static final Logger log = LoggerFactory.getLogger(AbstractReportGenerator.class);

    protected final EndOfDayReportRepository reportRepository;
    protected final WhatsAppPort whatsAppPort;
    protected final MultiStoreSummaryService multiStoreSummaryService;

    protected AbstractReportGenerator(EndOfDayReportRepository reportRepository,
                                      WhatsAppPort whatsAppPort,
                                      MultiStoreSummaryService multiStoreSummaryService) {
        this.reportRepository = reportRepository;
        this.whatsAppPort = whatsAppPort;
        this.multiStoreSummaryService = multiStoreSummaryService;
    }

    /**
     * Template Method — defines the algorithm skeleton.
     * Final: subclasses cannot change the order of steps.
     * @Transactional ensures PENDING report is persisted before delivery;
     * on crash mid-delivery the PENDING status can be retried.
     */
    @Transactional
    public final EndOfDayReport generateReport(GenerateEndOfDayReportUseCase.GenerateReportCommand command) {
        // Step 1: Collect data (subclass-specific)
        EndOfDayReportData data = collectData(command);

        // Idempotency guard: if a report already exists for this (store, date, type, actor), skip creation
        var existing = reportRepository.findByKey(
                command.storeId(), data.reportDate(), getReportType(), command.actorId());
        if (existing.isPresent()) {
            log.warn("Duplicate report skipped — storeId={}, date={}, type={}, actorId={}",
                    command.storeId(), data.reportDate(), getReportType(), command.actorId());
            return existing.get();
        }

        // Step 2: Format content (subclass-specific)
        String content = formatContent(data);

        // Step 3: Persist with PENDING status (shared)
        EndOfDayReport report = persistReport(command, data, content);

        // Step 4: Deliver via WhatsApp (shared)
        deliverReport(report, command);

        // Step 5: Check multi-store condition (shared)
        checkMultiStoreCondition(command);

        return report;
    }

    // ── Abstract steps (subclass-specific) ────────────────────────────────────

    protected abstract EndOfDayReportData collectData(GenerateEndOfDayReportUseCase.GenerateReportCommand command);

    protected abstract String formatContent(EndOfDayReportData data);

    protected abstract ReportType getReportType();

    // ── Concrete steps (shared by all report types) ────────────────────────────

    protected EndOfDayReport persistReport(GenerateEndOfDayReportUseCase.GenerateReportCommand command,
                                           EndOfDayReportData data, String content) {
        EndOfDayReport report = EndOfDayReport.createNew(
                command.tenantId(), command.storeId(), data.storeName(),
                command.actorId(),
                getReportType(), data.reportDate(), content,
                data.totalRevenue(), data.totalSales(), command.isAutomatic()
        );
        reportRepository.save(report);
        log.info("Report persisted: id={}, store={}, type={}", report.getId(), command.storeId(), getReportType());
        return report;
    }

    protected void deliverReport(EndOfDayReport report,
                                 GenerateEndOfDayReportUseCase.GenerateReportCommand command) {
        // Story 7.5 — honour per-tenant delivery channel
        ReportChannel channel = command.deliveryChannel();
        if (channel == ReportChannel.IN_APP_ONLY) {
            report.markInAppOnly();
            reportRepository.save(report);
            log.info("Report marked IN_APP_ONLY per tenant preference: reportId={}", report.getId());
            return;
        }

        String ownerPhone = resolveOwnerPhone(command);
        if (ownerPhone == null) {
            log.warn("Owner phone not resolved for tenantId={} — report {} marked IN_APP_ONLY",
                    command.tenantId(), report.getId());
            report.markInAppOnly();
            reportRepository.save(report);
            return;
        }
        try {
            whatsAppPort.sendReport(ownerPhone, report.getContent());
            report.markSent();
            reportRepository.save(report);
            log.info("Report delivered via WhatsApp: reportId={}", report.getId());
        } catch (Exception e) {
            log.warn("WhatsApp delivery failed for reportId={}: {}", report.getId(), e.getMessage());
            report.incrementAttempt();
            report.markFailed();
            reportRepository.save(report);
        }
    }

    protected void checkMultiStoreCondition(GenerateEndOfDayReportUseCase.GenerateReportCommand command) {
        try {
            multiStoreSummaryService.checkAndGenerateCombinedSummary(command);
        } catch (Exception e) {
            log.warn("Multi-store summary check failed: {}", e.getMessage());
        }
    }

    /**
     * Resolve the owner's phone number for WhatsApp delivery.
     * Subclasses may override for different resolution strategies.
     * Default: delegated to abstract method to be provided by concrete class.
     */
    protected abstract String resolveOwnerPhone(GenerateEndOfDayReportUseCase.GenerateReportCommand command);
}
