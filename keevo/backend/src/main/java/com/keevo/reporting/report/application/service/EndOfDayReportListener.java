package com.keevo.reporting.report.application.service;

import com.keevo.commerce.api.ClosureWindow;
import com.keevo.shared.event.catalog.sale.DayClosedEvent;
import com.keevo.identity.api.ReportChannel;
import com.keevo.identity.api.TenantPreferencesQueryPort;
import com.keevo.reporting.report.domain.port.in.GenerateEndOfDayReportUseCase;
import com.keevo.reporting.report.domain.port.in.GenerateEndOfDayReportUseCase.GenerateReportCommand;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.module.ModuleScoped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.event.TransactionPhase;

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
 *
 * <p>Story 13.9 — {@code @ModuleScoped("reporting")}: for a tenant whose « Rapports » module is DISABLED, the
 * closure produces no report (the publication is completed, nothing generated); READ_ONLY still generates (a report
 * is a read of past data).
 */
@Component
@ModuleScoped("reporting")
public class EndOfDayReportListener {

    private static final Logger log = LoggerFactory.getLogger(EndOfDayReportListener.class);

    private final GenerateEndOfDayReportUseCase generateReportUseCase;
    private final EndOfDayReportBuilder builder;
    private final TenantPreferencesQueryPort preferencesQuery;

    public EndOfDayReportListener(GenerateEndOfDayReportUseCase generateReportUseCase,
                                  EndOfDayReportBuilder builder,
                                  TenantPreferencesQueryPort preferencesQuery) {
        this.generateReportUseCase = generateReportUseCase;
        this.builder = builder;
        this.preferencesQuery = preferencesQuery;
    }

    /**
     * Fires AFTER the DayClosure transaction commits (AFTER_COMMIT), synchronously in the
     * same request thread. Running synchronously ensures the report is always persisted before
     * the HTTP response returns — no data loss on JVM restart.
     *
     * <p>Story 12.4 (orchestrator ruling R1): stays synchronous; the Spring Modulith registry records
     * the publication with the closure, so a crash between commit and report generation is replayed
     * (restart / scheduled resubmission). Replay-safe: each report is generated in its own transaction
     * and skipped when one already exists for (store, date, type, actor).
     *
     * <p>WhatsApp delivery is best-effort (try/catch inside deliverReport): if it fails the
     * report stays PENDING and can be resent later.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDayClosed(DayClosedEvent event) {
        log.info("EndOfDayReportListener: DayClosedEvent received storeId={}, tenantId={}, isAutomatic={}",
                event.storeId(), event.tenantId(), event.isAutomatic());

        // Restored afterwards: on the automatic closure path this runs on the scheduler
        // thread, which goes on closing the tenant's other stores (v1s-16-22).
        String previousTenant = TenantContext.getCurrentTenant();
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
                    event.occurredAt(),   // Fix H1: actual closure time for ⏰ display
                    event.windowStart(),
                    event.windowEnd(),    // Fix H1: calendar window end (23:59:59 WAT) for queries
                    channel,
                    event.lateFrom(),
                    event.receivedUntil()
            );
            generateReportUseCase.generateReport(storeCommand);

            // 2. Generate one personal report per employee with sales in this window
            List<UUID> employeeIds = builder.getDistinctEmployeeIds(event.storeId(), ClosureWindow.of(event));

            for (UUID employeeId : employeeIds) {
                try {
                    var empCommand = new GenerateReportCommand(
                            event.storeId(),
                            employeeId,
                            event.tenantId(),
                            event.isAutomatic(),
                            event.occurredAt(),   // Fix H1: actual closure time for ⏰ display
                            event.windowStart(),
                            event.windowEnd(),    // Fix H1: calendar window end (23:59:59 WAT) for queries
                            channel,
                            event.lateFrom(),
                            event.receivedUntil()
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
            if (previousTenant != null) {
                TenantContext.setCurrentTenant(previousTenant);
            } else {
                TenantContext.clear();
            }
        }
    }

    /**
     * Returns the configured EOD delivery channel, or null if EOD reports are disabled.
     * Defaults to WHATSAPP when no preference record exists (safe fallback for legacy tenants).
     */
    private ReportChannel resolveEodChannel() {
        return preferencesQuery.find(TenantContext.getCurrentTenant())
                .map(prefs -> prefs.eodReportEnabled()
                        ? (prefs.eodReportChannel() != null ? prefs.eodReportChannel() : ReportChannel.WHATSAPP)
                        : null)
                .orElse(ReportChannel.WHATSAPP);
    }
}
