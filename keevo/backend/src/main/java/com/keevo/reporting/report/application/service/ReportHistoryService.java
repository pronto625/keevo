package com.keevo.reporting.report.application.service;

import com.keevo.reporting.report.domain.model.EndOfDayReport;
import com.keevo.reporting.report.domain.model.ReportType;
import com.keevo.reporting.report.domain.port.in.GetReportHistoryUseCase;
import com.keevo.reporting.report.domain.port.in.ResendReportUseCase;
import com.keevo.reporting.report.domain.port.out.EndOfDayReportRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.messaging.whatsapp.domain.port.out.WhatsAppPort;
import com.keevo.identity.auth.domain.port.out.UserRepository;
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
    private static final String DEFAULT_OWNER_PHONE = "+243000000000";

    private final EndOfDayReportRepository reportRepository;
    private final WhatsAppPort whatsAppPort;
    private final UserRepository userRepository;

    public ReportHistoryService(EndOfDayReportRepository reportRepository,
                                 WhatsAppPort whatsAppPort,
                                 UserRepository userRepository) {
        this.reportRepository = reportRepository;
        this.whatsAppPort = whatsAppPort;
        this.userRepository = userRepository;
    }

    @Override
    public Page<EndOfDayReport> getReportHistory(ReportHistoryQuery query) {
        return reportRepository.findFiltered(
                query.tenantId(),
                query.storeId(),
                query.actorId(),
                query.type(),
                query.pageable()
        );
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

        // Reset and attempt immediate delivery (no guard: re-sending an already-SENT report is allowed)
        report.resetForResend();
        reportRepository.save(report);

        String ownerPhone = userRepository.findOwnerByTenantSchemaName(command.tenantId())
                .map(u -> u.getPhoneNumber())
                .orElse(DEFAULT_OWNER_PHONE);

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
