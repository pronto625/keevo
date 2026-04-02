package com.keevo.reporting.report.application.service;

import com.keevo.commerce.sale.domain.model.DayClosedEvent;
import com.keevo.identity.onboarding.domain.model.ReportChannel;
import com.keevo.identity.onboarding.domain.model.TenantPreferences;
import com.keevo.identity.onboarding.domain.port.out.TenantPreferencesRepository;
import com.keevo.reporting.report.domain.port.in.GenerateEndOfDayReportUseCase;
import com.keevo.reporting.report.domain.port.in.GenerateEndOfDayReportUseCase.GenerateReportCommand;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * EndOfDayReportListener — Spring Observer: listens to DayClosedEvent and generates reports.
 * Story 7.2 — Task 6.4 (GREEN).
 * Story 7.5 — Reads per-tenant eodReportEnabled + eodReportChannel before generating.
 *
 * <p>For each closure it generates:
 * <ol>
 *   <li>One store-level report (actorId = null) → visible to owner only</li>
 *   <li>One personal report per employee who made sales in the window (actorId = employeeId)</li>
 * </ol>
 */
@Component
public class EndOfDayReportListener {

    private static final Logger log = LoggerFactory.getLogger(EndOfDayReportListener.class);

    private final GenerateEndOfDayReportUseCase generateReportUseCase;
    private final EndOfDayReportBuilder builder;
    private final TenantPreferencesRepository tenantPreferencesRepository;

    public EndOfDayReportListener(GenerateEndOfDayReportUseCase generateReportUseCase,
                                  EndOfDayReportBuilder builder,
                                  TenantPreferencesRepository tenantPreferencesRepository) {
        this.generateReportUseCase = generateReportUseCase;
        this.builder = builder;
        this.tenantPreferencesRepository = tenantPreferencesRepository;
    }

    @EventListener
    public void onDayClosed(DayClosedEvent event) {
        log.info("EndOfDayReportListener: DayClosedEvent received storeId={}, tenantId={}, isAutomatic={}",
                event.storeId(), event.tenantId(), event.isAutomatic());

        TenantContext.setCurrentTenant(event.tenantId());
        try {
            // Story 7.5 — check if EOD report is enabled for this tenant
            ReportChannel channel = resolveEodChannel();
            if (channel == null) {
                // eodReportEnabled == false — skip report generation entirely
                log.info("EOD report disabled for tenant={}, skipping.", event.tenantId());
                return;
            }

            // 1. Generate the store-level report (actorId = null → owner sees it)
            var storeCommand = new GenerateReportCommand(
                    event.storeId(),
                    null,
                    event.tenantId(),
                    event.isAutomatic(),
                    event.occurredAt(),
                    event.windowStart(),
                    channel
            );
            generateReportUseCase.generateReport(storeCommand);

            // 2. Generate one personal report per employee with sales in this window
            List<UUID> employeeIds = builder.getDistinctEmployeeIds(
                    event.storeId(), event.windowStart(), event.occurredAt());

            for (UUID employeeId : employeeIds) {
                try {
                    var empCommand = new GenerateReportCommand(
                            event.storeId(),
                            employeeId,
                            event.tenantId(),
                            event.isAutomatic(),
                            event.occurredAt(),
                            event.windowStart(),
                            channel
                    );
                    generateReportUseCase.generateReport(empCommand);
                } catch (Exception e) {
                    log.error("Failed to generate employee report for employeeId={}: {}",
                            employeeId, e.getMessage(), e);
                }
            }
        } catch (Exception e) {
            log.error("Failed to generate reports for storeId={}: {}", event.storeId(), e.getMessage(), e);
        } finally {
            TenantContext.clear();
        }
    }

    /**
     * Returns the configured EOD delivery channel, or null if EOD reports are disabled.
     * Defaults to WHATSAPP when no preference record exists (safe fallback for legacy tenants).
     */
    private ReportChannel resolveEodChannel() {
        return tenantPreferencesRepository.findByCurrentTenant()
                .map(prefs -> prefs.eodReportEnabled()
                        ? (prefs.eodReportChannel() != null ? prefs.eodReportChannel() : ReportChannel.WHATSAPP)
                        : null)
                .orElse(ReportChannel.WHATSAPP);
    }
}
