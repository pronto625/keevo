package com.keevo.reporting.report.application.service;

import com.keevo.shared.capability.notification.WhatsAppSender;
import com.keevo.identity.api.TenantView;
import com.keevo.identity.api.TenantStatus;
import com.keevo.identity.api.TenantQueryPort;
import com.keevo.identity.api.OwnerRecipientResolver;
import com.keevo.reporting.report.domain.model.EndOfDayReport;
import com.keevo.reporting.report.domain.port.out.EndOfDayReportRepository;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.keevo.shared.module.ModuleRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * ReportDeliveryRetryService — Polls FAILED reports and retries WhatsApp delivery.
 * Story 7.2 — Task 6.5 (GREEN).
 *
 * <p>Runs every 5 minutes. Max 3 attempts. After 3 failures: IN_APP_ONLY.
 * Multi-tenant: iterates all active tenants (same pattern as DayClosureAutoScheduler).
 */
@Component
public class ReportDeliveryRetryService {

    private static final Logger log = LoggerFactory.getLogger(ReportDeliveryRetryService.class);
    static final int MAX_ATTEMPTS = 3;

    private final EndOfDayReportRepository reportRepository;
    private final WhatsAppSender whatsAppSender;
    private final TenantQueryPort tenantQuery;
    private final OwnerRecipientResolver ownerRecipientResolver;
    private final ModuleRegistry modules;

    /** Without module gate (every tenant processed) — unit tests. */
    public ReportDeliveryRetryService(EndOfDayReportRepository reportRepository,
                                       WhatsAppSender whatsAppSender,
                                       TenantQueryPort tenantQuery,
                                       OwnerRecipientResolver ownerRecipientResolver) {
        this(reportRepository, whatsAppSender, tenantQuery, ownerRecipientResolver, null);
    }

    /** Story 13.9: tenants whose « Rapports » module is DISABLED are skipped (READ_ONLY still retried). */
    @Autowired
    public ReportDeliveryRetryService(EndOfDayReportRepository reportRepository,
                                       WhatsAppSender whatsAppSender,
                                       TenantQueryPort tenantQuery,
                                       OwnerRecipientResolver ownerRecipientResolver,
                                       ModuleRegistry modules) {
        this.modules = modules;
        this.reportRepository = reportRepository;
        this.whatsAppSender = whatsAppSender;
        this.tenantQuery = tenantQuery;
        this.ownerRecipientResolver = ownerRecipientResolver;
    }

    @Scheduled(fixedDelay = 300_000)  // every 5 minutes
    @SchedulerLock(name = "reportDeliveryRetryService.retryFailedDeliveries", lockAtMostFor = "PT15M", lockAtLeastFor = "PT30S")
    public void retryFailedDeliveries() {
        log.debug("ReportDeliveryRetryService: checking failed reports");

        for (TenantView tenant : tenantQuery.findAll()) {
            if (tenant.status() != TenantStatus.ACTIVE) continue;
            if (ReportingModuleGate.disabled(modules, tenant.schemaName(), "ReportDeliveryRetryService")) continue;

            try {
                TenantContext.setCurrentTenant(tenant.schemaName());
                retryForTenant(tenant);
            } catch (Exception e) {
                log.error("Error retrying reports for tenant {}: {}", tenant.schemaName(), e.getMessage());
            } finally {
                TenantContext.clear();
            }
        }
    }

    private void retryForTenant(TenantView tenant) {
        List<EndOfDayReport> pendingRetries = reportRepository.findPendingRetries(MAX_ATTEMPTS);
        if (pendingRetries.isEmpty()) return;

        Optional<String> ownerPhoneOpt = ownerRecipientResolver.resolveOwnerWhatsAppPhone(tenant.schemaName());
        if (ownerPhoneOpt.isEmpty()) {
            log.warn("No usable owner WhatsApp phone for tenant {} — skipping retry for {} report(s)",
                    tenant.schemaName(), pendingRetries.size());
            return;
        }
        String ownerPhone = ownerPhoneOpt.get();

        for (EndOfDayReport report : pendingRetries) {
            try {
                whatsAppSender.sendReport(ownerPhone, report.getContent());
                report.markSent();
                log.info("Retry succeeded for reportId={}", report.getId());
            } catch (Exception e) {
                report.incrementAttempt();
                if (report.getDeliveryAttempts() >= MAX_ATTEMPTS) {
                    report.markInAppOnly();
                    log.warn("Max retries reached for reportId={} — marking IN_APP_ONLY", report.getId());
                } else {
                    report.markFailed();
                    log.warn("Retry failed for reportId={}, attempts={}", report.getId(), report.getDeliveryAttempts());
                }
            }
            reportRepository.save(report);
        }
    }
}
