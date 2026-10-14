package com.keevo.reporting.report.application.service;

import com.keevo.messaging.whatsapp.domain.port.out.WhatsAppPort;
import com.keevo.identity.auth.domain.port.out.OwnerRecipientResolver;
import com.keevo.reporting.report.domain.model.DeliveryStatus;
import com.keevo.reporting.report.domain.model.EndOfDayReport;
import com.keevo.reporting.report.domain.model.ReportType;
import com.keevo.reporting.report.domain.port.in.GetReportHistoryUseCase.ReportHistoryQuery;
import com.keevo.reporting.report.domain.port.in.ResendReportUseCase.ResendReportCommand;
import com.keevo.reporting.report.domain.port.out.EndOfDayReportRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * ReportHistoryServiceTest — characterizes ReportHistoryService behavior.
 * Story 15.3 — Task 3.
 */
@ExtendWith(MockitoExtension.class)
class ReportHistoryServiceTest {

    @Mock private EndOfDayReportRepository reportRepository;
    @Mock private WhatsAppPort whatsAppPort;
    @Mock private OwnerRecipientResolver ownerRecipientResolver;
    @Mock private com.keevo.identity.employee.domain.port.out.EmployeeRepository employeeRepository;

    private ReportHistoryService service;
    private UUID reportId;
    private String tenantId;
    private EndOfDayReport sampleReport;

    @BeforeEach
    void setUp() {
        service = new ReportHistoryService(reportRepository, whatsAppPort, ownerRecipientResolver,
                employeeRepository);
        reportId = UUID.randomUUID();
        tenantId = "kv_test01";
        sampleReport = EndOfDayReport.createNew(
                tenantId, UUID.randomUUID(), "Boutique Test",
                null, null,
                ReportType.DAILY, LocalDate.now(), "Contenu", 100000, 5, false);
    }

    // ─────────────────────────────────────────────────────────────────
    // 3.2 — getReportHistory delegates to repository
    // ─────────────────────────────────────────────────────────────────

    @Test
    void getReportHistory_delegatesToRepositoryFindFiltered_withAllQueryFields() {
        UUID storeId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        PageRequest pageable = PageRequest.of(0, 10);
        ReportHistoryQuery query = new ReportHistoryQuery(tenantId, storeId, actorId, ReportType.DAILY, pageable);
        Page<EndOfDayReport> page = new PageImpl<>(List.of(sampleReport));
        when(reportRepository.findFiltered(tenantId, storeId, actorId, ReportType.DAILY, pageable)).thenReturn(page);

        Page<EndOfDayReport> result = service.getReportHistory(query);

        assertThat(result.getContent()).hasSize(1);
        verify(reportRepository).findFiltered(tenantId, storeId, actorId, ReportType.DAILY, pageable);
    }

    @Test
    void getReportHistory_employeeRecordId_isTranslatedToTheUserIdStoredOnReports() {
        UUID storeId = UUID.randomUUID();
        UUID employeeId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        PageRequest pageable = PageRequest.of(0, 10);
        when(employeeRepository.findById(employeeId)).thenReturn(Optional.of(
                new com.keevo.identity.employee.domain.model.Employee(employeeId, userId, storeId,
                        "Jean", "Kamga",
                        com.keevo.identity.employee.domain.model.EmployeeStatus.ACTIVE,
                        false, java.time.Instant.now())));
        when(reportRepository.findFiltered(tenantId, storeId, userId, null, pageable))
                .thenReturn(new PageImpl<>(List.of(sampleReport)));

        Page<EndOfDayReport> result = service.getReportHistory(
                new ReportHistoryQuery(tenantId, storeId, employeeId, null, pageable));

        assertThat(result.getContent()).hasSize(1);
        verify(reportRepository).findFiltered(tenantId, storeId, userId, null, pageable);
    }

    @Test
    void getReportHistory_userId_isUsedAsIs() {
        UUID userId = UUID.randomUUID();
        PageRequest pageable = PageRequest.of(0, 10);
        when(employeeRepository.findById(userId)).thenReturn(Optional.empty());
        when(reportRepository.findFiltered(tenantId, null, userId, null, pageable))
                .thenReturn(new PageImpl<>(List.of(sampleReport)));

        service.getReportHistory(new ReportHistoryQuery(tenantId, null, userId, null, pageable));

        verify(reportRepository).findFiltered(tenantId, null, userId, null, pageable);
    }

    // ─────────────────────────────────────────────────────────────────
    // 3.3 — getReportById tenant isolation
    // ─────────────────────────────────────────────────────────────────

    @Test
    void getReportById_whenTenantMatches_returnsReport() {
        when(reportRepository.findById(reportId)).thenReturn(Optional.of(sampleReport));

        Optional<EndOfDayReport> result = service.getReportById(reportId, tenantId);

        assertTrue(result.isPresent());
        assertThat(result.get().getTenantId()).isEqualTo(tenantId);
    }

    @Test
    void getReportById_whenTenantMismatch_returnsEmpty() {
        // Same report but different tenant — isolation guard
        when(reportRepository.findById(reportId)).thenReturn(Optional.of(sampleReport));

        Optional<EndOfDayReport> result = service.getReportById(reportId, "kv_other_tenant");

        assertTrue(result.isEmpty());
    }

    @Test
    void getReportById_whenNotFound_returnsEmpty() {
        when(reportRepository.findById(reportId)).thenReturn(Optional.empty());

        Optional<EndOfDayReport> result = service.getReportById(reportId, tenantId);

        assertTrue(result.isEmpty());
    }

    // ─────────────────────────────────────────────────────────────────
    // 3.4 — resendReport when report not found
    // ─────────────────────────────────────────────────────────────────

    @Test
    void resendReport_whenReportNotFound_throwsDomainException_NOT_FOUND() {
        when(reportRepository.findById(reportId)).thenReturn(Optional.empty());

        DomainException ex = assertThrows(DomainException.class,
                () -> service.resendReport(new ResendReportCommand(reportId, tenantId)));
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND);
    }

    // ─────────────────────────────────────────────────────────────────
    // 3.5 — resendReport tenant mismatch
    // ─────────────────────────────────────────────────────────────────

    @Test
    void resendReport_whenTenantMismatch_throwsDomainException_NOT_FOUND() {
        when(reportRepository.findById(reportId)).thenReturn(Optional.of(sampleReport));

        DomainException ex = assertThrows(DomainException.class,
                () -> service.resendReport(new ResendReportCommand(reportId, "kv_other")));
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND);
    }

    // ─────────────────────────────────────────────────────────────────
    // 3.6 — resendReport WhatsApp success: reset + increment + markSent + 2 saves
    // ─────────────────────────────────────────────────────────────────

    @Test
    void resendReport_whenWhatsAppSucceeds_resetsIncrementsMarksSentAndSavesTwice() {
        when(reportRepository.findById(reportId)).thenReturn(Optional.of(sampleReport));
        when(ownerRecipientResolver.resolveOwnerWhatsAppPhone(tenantId)).thenReturn(Optional.of("+237600000001"));

        service.resendReport(new ResendReportCommand(reportId, tenantId));

        // Two saves: one after resetForResend(), one after markSent()
        verify(reportRepository, times(2)).save(any(EndOfDayReport.class));
        verify(whatsAppPort).sendReport("+237600000001", "Contenu");
        assertThat(sampleReport.getDeliveryAttempts()).isEqualTo(1);
        assertThat(sampleReport.getDeliveryStatus()).isEqualTo(DeliveryStatus.SENT);
    }

    // ─────────────────────────────────────────────────────────────────
    // 3.7 — resendReport WhatsApp fails: markFailed + save + throw
    // ─────────────────────────────────────────────────────────────────

    @Test
    void resendReport_whenWhatsAppFails_marksFailedSavesAndThrowsDeliveryException() {
        when(reportRepository.findById(reportId)).thenReturn(Optional.of(sampleReport));
        when(ownerRecipientResolver.resolveOwnerWhatsAppPhone(tenantId)).thenReturn(Optional.of("+237600000001"));
        doThrow(new RuntimeException("net error")).when(whatsAppPort).sendReport(anyString(), anyString());

        DomainException ex = assertThrows(DomainException.class,
                () -> service.resendReport(new ResendReportCommand(reportId, tenantId)));
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.WHATSAPP_DELIVERY_FAILED);
        assertThat(sampleReport.getDeliveryStatus()).isEqualTo(DeliveryStatus.FAILED);
        assertThat(sampleReport.getDeliveryAttempts()).isEqualTo(1);
        // 2 saves: one after reset, one after markFailed
        verify(reportRepository, times(2)).save(any(EndOfDayReport.class));
    }

    // ─────────────────────────────────────────────────────────────────
    // 3.8 — v1s-15-7 D1: no owner phone → refused, nothing sent, report untouched
    // ─────────────────────────────────────────────────────────────────

    /** v1s-14-17: missing owner, null / blank / invalid phone all surface as an empty resolver result. */
    @Test
    void resendReport_whenNoOwnerRecipient_throwsDeliveryFailed_andNeverSendsOrResets() {
        when(reportRepository.findById(reportId)).thenReturn(Optional.of(sampleReport));
        when(ownerRecipientResolver.resolveOwnerWhatsAppPhone(tenantId)).thenReturn(Optional.empty());

        assertResendRefusedWithoutTouchingReport();
    }

    private void assertResendRefusedWithoutTouchingReport() {
        sampleReport.incrementAttempt();
        sampleReport.markFailed();

        DomainException ex = assertThrows(DomainException.class,
                () -> service.resendReport(new ResendReportCommand(reportId, tenantId)));

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.WHATSAPP_DELIVERY_FAILED);
        verifyNoInteractions(whatsAppPort);
        verify(reportRepository, never()).save(any());
        assertThat(sampleReport.getDeliveryStatus()).isEqualTo(DeliveryStatus.FAILED);
        assertThat(sampleReport.getDeliveryAttempts()).isEqualTo(1);
    }

    // ─────────────────────────────────────────────────────────────────
    // 3.9 — v1s-15-7 item 10: resend of an already SENT / FAILED report is allowed
    // ─────────────────────────────────────────────────────────────────

    @Test
    void resendReport_whenAlreadySent_resendsAndResetsAttemptsToOne() {
        sampleReport.incrementAttempt();
        sampleReport.markSent();
        stubOwnerPhone();

        service.resendReport(new ResendReportCommand(reportId, tenantId));

        verify(whatsAppPort).sendReport("+237600000001", "Contenu");
        assertThat(sampleReport.getDeliveryAttempts()).isEqualTo(1);
        assertThat(sampleReport.getDeliveryStatus()).isEqualTo(DeliveryStatus.SENT);
    }

    @Test
    void resendReport_whenFailedAfterThreeAttempts_resendsAndResetsAttemptsToOne() {
        for (int i = 0; i < 3; i++) {
            sampleReport.incrementAttempt();
        }
        sampleReport.markFailed();
        stubOwnerPhone();

        service.resendReport(new ResendReportCommand(reportId, tenantId));

        verify(whatsAppPort).sendReport("+237600000001", "Contenu");
        assertThat(sampleReport.getDeliveryAttempts()).isEqualTo(1);
        assertThat(sampleReport.getDeliveryStatus()).isEqualTo(DeliveryStatus.SENT);
    }

    private void stubOwnerPhone() {
        when(reportRepository.findById(reportId)).thenReturn(Optional.of(sampleReport));
        when(ownerRecipientResolver.resolveOwnerWhatsAppPhone(tenantId)).thenReturn(Optional.of("+237600000001"));
    }
}
