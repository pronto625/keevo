package com.keevo.reporting.report.application.service;

import com.keevo.commerce.sale.domain.port.out.WhatsAppPort;
import com.keevo.identity.auth.domain.model.Tenant;
import com.keevo.identity.auth.domain.model.TenantStatus;
import com.keevo.identity.auth.domain.port.out.TenantRepository;
import com.keevo.identity.auth.domain.port.out.UserRepository;
import com.keevo.reporting.report.domain.model.EndOfDayReport;
import com.keevo.reporting.report.domain.port.out.EndOfDayReportRepository;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private final WhatsAppPort whatsAppPort;
    private final TenantRepository tenantRepository;
    private final UserRepository userRepository;
    public ReportDeliveryRetryService(EndOfDayReportRepository reportRepository,
                                       WhatsAppPort whatsAppPort,
                                       TenantRepository tenantRepository,
                                       UserRepository userRepository) {
        this.reportRepository = reportRepository;
        this.whatsAppPort = whatsAppPort;
        this.tenantRepository = tenantRepository;
        this.userRepository = userRepository;
    }

    @Scheduled(fixedDelay = 300_000)  // every 5 minutes
    public void retryFailedDeliveries() {
        log.debug("ReportDeliveryRetryService: checking failed reports");

        for (Tenant tenant : tenantRepository.findAll()) {
            if (tenant.getStatus() != TenantStatus.ACTIVE) continue;

            try {
                TenantContext.setCurrentTenant(tenant.getSchemaName());
                retryForTenant(tenant);
            } catch (Exception e) {
                log.error("Error retrying reports for tenant {}: {}", tenant.getSchemaName(), e.getMessage());
            } finally {
                TenantContext.clear();
            }
        }
    }

    private void retryForTenant(Tenant tenant) {
        List<EndOfDayReport> pendingRetries = reportRepository.findPendingRetries(MAX_ATTEMPTS);
        if (pendingRetries.isEmpty()) return;

        Optional<String> ownerPhoneOpt = userRepository.findOwnerByTenantSchemaName(tenant.getSchemaName())
                .map(u -> u.getPhoneNumber());
        if (ownerPhoneOpt.isEmpty()) {
            log.warn("No owner found for tenant {} — skipping retry for {} report(s)",
                    tenant.getSchemaName(), pendingRetries.size());
            return;
        }
        String ownerPhone = ownerPhoneOpt.get();

        for (EndOfDayReport report : pendingRetries) {
            try {
                whatsAppPort.sendReport(ownerPhone, report.getContent());
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
