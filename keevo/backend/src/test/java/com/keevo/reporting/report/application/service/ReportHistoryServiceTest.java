package com.keevo.reporting.report.application.service;

import com.keevo.messaging.whatsapp.domain.port.out.WhatsAppPort;
import com.keevo.identity.auth.domain.model.User;
import com.keevo.identity.auth.domain.port.out.UserRepository;
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
    @Mock private UserRepository userRepository;

    private ReportHistoryService service;
    private UUID reportId;
    private String tenantId;
    private EndOfDayReport sampleReport;

    @BeforeEach
    void setUp() {
        service = new ReportHistoryService(reportRepository, whatsAppPort, userRepository);
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
        User owner = mock(User.class);
        when(owner.getPhoneNumber()).thenReturn("+237600000001");
        when(userRepository.findOwnerByTenantSchemaName(tenantId)).thenReturn(Optional.of(owner));

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
        User owner = mock(User.class);
        when(owner.getPhoneNumber()).thenReturn("+237600000001");
        when(userRepository.findOwnerByTenantSchemaName(tenantId)).thenReturn(Optional.of(owner));
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
    // 3.8 — resendReport uses default phone when owner not resolved
    // ─────────────────────────────────────────────────────────────────

    @Test
    void resendReport_whenOwnerPhoneNotResolved_usesDefaultPhoneConstant() {
        when(reportRepository.findById(reportId)).thenReturn(Optional.of(sampleReport));
        when(userRepository.findOwnerByTenantSchemaName(tenantId)).thenReturn(Optional.empty());

        service.resendReport(new ResendReportCommand(reportId, tenantId));

        verify(whatsAppPort).sendReport("+243000000000", "Contenu");
    }
}
