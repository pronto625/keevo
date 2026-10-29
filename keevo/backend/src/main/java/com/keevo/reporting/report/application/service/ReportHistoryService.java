package com.keevo.reporting.report.application.service;

import com.keevo.reporting.report.domain.model.EndOfDayReport;
import com.keevo.reporting.report.domain.model.ReportType;
import com.keevo.reporting.report.domain.port.in.GetReportHistoryUseCase;
import com.keevo.reporting.report.domain.port.in.ResendReportUseCase;
import com.keevo.reporting.report.domain.port.out.EndOfDayReportRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.messaging.api.WhatsAppPort;
import com.keevo.identity.api.OwnerRecipientResolver;
import com.keevo.identity.api.EmployeeView;
import com.keevo.identity.api.EmployeeQueryPort;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

/**
 * ReportHistoryService — Implements GetReportHistoryUseCase + ResendReportUseCase.
 * Story 7.2 — Task 6.7 (GREEN).
 */
@Service
public class ReportHistoryService implements GetReportHistoryUseCase, ResendReportUseCase {

    private static final Logger log = LoggerFactory.getLogger(ReportHistoryService.class);

    private final EndOfDayReportRepository reportRepository;
    private final WhatsAppPort whatsAppPort;
    private final OwnerRecipientResolver ownerRecipientResolver;
    private final EmployeeQueryPort employeeQuery;

    public ReportHistoryService(EndOfDayReportRepository reportRepository,
                                 WhatsAppPort whatsAppPort,
                                 OwnerRecipientResolver ownerRecipientResolver,
                                 EmployeeQueryPort employeeQuery) {
        this.reportRepository = reportRepository;
        this.whatsAppPort = whatsAppPort;
        this.ownerRecipientResolver = ownerRecipientResolver;
        this.employeeQuery = employeeQuery;
    }

    @Override
    public Page<EndOfDayReport> getReportHistory(ReportHistoryQuery query) {
        return reportRepository.findFiltered(
                query.tenantId(),
                query.storeId(),
                reportActorId(query.actorId()),
                query.type(),
                query.pageable()
        );
    }

    /**
     * Un rapport personnel porte l'id UTILISATEUR du vendeur. Si le filtre
     * reçoit l'id de la fiche employé, on le traduit en id utilisateur pour
     * que « Rapports de la boutique » filtré par employé trouve ses rapports.
     */
    private UUID reportActorId(UUID actorId) {
        if (actorId == null) return null;
        return employeeQuery.findById(TenantContext.getCurrentTenant(), actorId)
                .map(EmployeeView::userId)
                .filter(java.util.Objects::nonNull)
                .orElse(actorId);
    }

    @Override
    public Optional<EndOfDayReport> getReportById(UUID reportId, String tenantId) {
        return reportRepository.findById(reportId)
                .filter(r -> r.getTenantId().equals(tenantId));
    }

    @Override
    public void resendReport(ResendReportCommand command) {
        EndOfDayReport report = reportRepository.findById(command.reportId())
                .filter(r -> r.getTenantId().equals(command.tenantId()))
                .orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "Report not found"));

        // v1s-15-7 D1: never guess a recipient. Without a resolvable owner phone the resend is
        // refused before the report is touched (no reset, no save, no WhatsApp call) — same rule
        // as ReportDeliveryRetryService (skip) and DailyReportGenerator (IN_APP_ONLY).
        String ownerPhone = ownerRecipientResolver.resolveOwnerWhatsAppPhone(command.tenantId())
                .orElseThrow(() -> {
                    log.warn("Resend refused for reportId={} tenant={}: no owner WhatsApp phone",
                            report.getId(), command.tenantId());
                    return new DomainException(ErrorCode.WHATSAPP_DELIVERY_FAILED,
                            "Aucun numéro WhatsApp du propriétaire pour renvoyer le rapport");
                });

        // Reset and attempt immediate delivery (no guard: re-sending an already-SENT report is allowed)
        report.resetForResend();
        reportRepository.save(report);

        try {
            whatsAppPort.sendReport(ownerPhone, report.getContent());
            report.incrementAttempt();
            report.markSent();
            reportRepository.save(report);
            log.info("Resend succeeded for reportId={}", report.getId());
        } catch (Exception e) {
            report.incrementAttempt();
            report.markFailed();
            reportRepository.save(report);
            log.warn("Resend failed for reportId={}: {}", report.getId(), e.getMessage());
            throw new DomainException(ErrorCode.WHATSAPP_DELIVERY_FAILED,
                    "WhatsApp delivery failed: " + e.getMessage());
        }
    }
}
