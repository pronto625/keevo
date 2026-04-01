package com.keevo.reporting.report.application.service;

import com.keevo.commerce.sale.domain.model.DayClosedEvent;
import com.keevo.reporting.report.domain.port.in.GenerateEndOfDayReportUseCase;
import com.keevo.reporting.report.domain.port.in.GenerateEndOfDayReportUseCase.GenerateReportCommand;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.reporting.report.application.service.EndOfDayReportBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * EndOfDayReportListener — Spring Observer: listens to DayClosedEvent and generates reports.
 * Story 7.2 — Task 6.4 (GREEN).
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

    public EndOfDayReportListener(GenerateEndOfDayReportUseCase generateReportUseCase,
                                  EndOfDayReportBuilder builder) {
        this.generateReportUseCase = generateReportUseCase;
        this.builder = builder;
    }

    @EventListener
    public void onDayClosed(DayClosedEvent event) {
        log.info("EndOfDayReportListener: DayClosedEvent received storeId={}, tenantId={}, isAutomatic={}",
                event.storeId(), event.tenantId(), event.isAutomatic());

        TenantContext.setCurrentTenant(event.tenantId());
        try {
            // 1. Generate the store-level report (actorId = null → owner sees it)
            var storeCommand = new GenerateReportCommand(
                    event.storeId(),
                    null,
                    event.tenantId(),
                    event.isAutomatic(),
                    event.occurredAt(),
                    event.windowStart()
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
                            event.windowStart()
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
}
