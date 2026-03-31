package com.keevo.reporting.report.application.service;

import com.keevo.commerce.sale.domain.model.DayClosedEvent;
import com.keevo.reporting.report.domain.port.in.GenerateEndOfDayReportUseCase;
import com.keevo.reporting.report.domain.port.in.GenerateEndOfDayReportUseCase.GenerateReportCommand;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * EndOfDayReportListener — Spring Observer: listens to DayClosedEvent and triggers report generation.
 * Story 7.2 — Task 6.4 (GREEN).
 *
 * <p>REPLACES DayClosureWhatsAppListener (story 4-4) which is disabled via @ConditionalOnProperty.
 */
@Component
public class EndOfDayReportListener {

    private static final Logger log = LoggerFactory.getLogger(EndOfDayReportListener.class);

    private final GenerateEndOfDayReportUseCase generateReportUseCase;

    public EndOfDayReportListener(GenerateEndOfDayReportUseCase generateReportUseCase) {
        this.generateReportUseCase = generateReportUseCase;
    }

    @EventListener
    public void onDayClosed(DayClosedEvent event) {
        log.info("EndOfDayReportListener: DayClosedEvent received storeId={}, tenantId={}, isAutomatic={}",
                event.storeId(), event.tenantId(), event.isAutomatic());

        // Ensure tenant context is set for the service layer
        TenantContext.setCurrentTenant(event.tenantId());
        try {
            var command = new GenerateReportCommand(
                    event.storeId(),
                    event.actorId(),
                    event.tenantId(),
                    event.isAutomatic(),
                    event.occurredAt()
            );
            generateReportUseCase.generateReport(command);
        } catch (Exception e) {
            log.error("Failed to generate report for storeId={}: {}", event.storeId(), e.getMessage(), e);
        } finally {
            TenantContext.clear();
        }
    }
}
