package com.keevo.reporting.report.application.service;

import com.keevo.identity.auth.adapter.in.api.IdentityAuthApiTestSupport;
import com.keevo.shared.capability.notification.WhatsAppSender;
import com.keevo.identity.auth.domain.model.Tenant;
import com.keevo.identity.api.TenantStatus;import com.keevo.identity.auth.domain.port.out.TenantRepository;
import com.keevo.identity.api.OwnerRecipientResolver;
import com.keevo.reporting.report.domain.model.DeliveryStatus;
import com.keevo.reporting.report.domain.model.EndOfDayReport;
import com.keevo.reporting.report.domain.model.ReportType;
import com.keevo.reporting.report.domain.port.out.EndOfDayReportRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * ReportDeliveryRetryServiceTest — TDD GREEN tests for the retry scheduler.
 * Story 7.2 — Task 2.3.
 */
@ExtendWith(MockitoExtension.class)
class ReportDeliveryRetryServiceTest {

    @Mock private EndOfDayReportRepository reportRepository;
    @Mock private WhatsAppSender whatsAppSender;
    @Mock private TenantRepository tenantRepository;
    @Mock private OwnerRecipientResolver ownerRecipientResolver;

    private ReportDeliveryRetryService service;
    private Tenant activeTenant;

    @BeforeEach
    void setUp() {
        service = new ReportDeliveryRetryService(reportRepository, whatsAppSender, IdentityAuthApiTestSupport.tenantQueryPort(tenantRepository), ownerRecipientResolver);
        activeTenant = mock(Tenant.class);
        lenient().when(activeTenant.getStatus()).thenReturn(TenantStatus.ACTIVE);
        lenient().when(activeTenant.getSchemaName()).thenReturn("kv_test01");
        lenient().when(tenantRepository.findAll()).thenReturn(List.of(activeTenant));
        // Default: owner phone is resolved
        lenient().when(ownerRecipientResolver.resolveOwnerWhatsAppPhone(anyString())).thenReturn(Optional.of("+237600000001"));
    }

    private EndOfDayReport failedReport(int attempts) {
        EndOfDayReport r = EndOfDayReport.createNew(
                "kv_test01", UUID.randomUUID(), "Boutique Test",
                null,
                null,  // actorName
                ReportType.DAILY, LocalDate.now(), "Contenu", 100000, 5, false);
        // Simulate previous failures
        for (int i = 0; i < attempts; i++) {
            r.incrementAttempt();
            r.markFailed();
        }
        return r;
    }

    @Test
    void retryFailedDeliveries_whenNoFailedReports_shouldNotCallWhatsApp() {
        when(reportRepository.findPendingRetries(anyInt())).thenReturn(List.of());
        service.retryFailedDeliveries();
        verify(whatsAppSender, never()).sendReport(anyString(), anyString());
    }

    @Test
    void retryFailedDeliveries_whenDeliverySucceeds_shouldMarkSent() {
        EndOfDayReport report = failedReport(1);
        when(reportRepository.findPendingRetries(anyInt())).thenReturn(List.of(report));
        service.retryFailedDeliveries();
        assertThat(report.getDeliveryStatus()).isEqualTo(DeliveryStatus.SENT);
    }

    @Test
    void retryFailedDeliveries_whenDeliveryFails_shouldMarkFailed() {
        EndOfDayReport report = failedReport(1);
        when(reportRepository.findPendingRetries(anyInt())).thenReturn(List.of(report));
        doThrow(new RuntimeException("net error")).when(whatsAppSender).sendReport(anyString(), anyString());
        service.retryFailedDeliveries();
        assertThat(report.getDeliveryStatus()).isEqualTo(DeliveryStatus.FAILED);
    }

    @Test
    void retryFailedDeliveries_whenMaxAttemptsReached_shouldMarkInAppOnly() {
        EndOfDayReport report = failedReport(ReportDeliveryRetryService.MAX_ATTEMPTS - 1);
        when(reportRepository.findPendingRetries(anyInt())).thenReturn(List.of(report));
        doThrow(new RuntimeException("net error")).when(whatsAppSender).sendReport(anyString(), anyString());
        service.retryFailedDeliveries();
        assertThat(report.getDeliveryStatus()).isEqualTo(DeliveryStatus.IN_APP_ONLY);
    }

    @Test
    void retryFailedDeliveries_shouldSkipInactiveTenants() {
        Tenant inactiveTenant = mock(Tenant.class);
        when(inactiveTenant.getStatus()).thenReturn(TenantStatus.SUSPENDED);
        when(tenantRepository.findAll()).thenReturn(List.of(inactiveTenant));
        service.retryFailedDeliveries();
        verify(reportRepository, never()).findPendingRetries(anyInt());
    }

    @Test
    void retryFailedDeliveries_shouldSaveAfterEachReport() {
        EndOfDayReport r1 = failedReport(1);
        EndOfDayReport r2 = failedReport(1);
        when(reportRepository.findPendingRetries(anyInt())).thenReturn(List.of(r1, r2));
        service.retryFailedDeliveries();
        verify(reportRepository, times(2)).save(any(EndOfDayReport.class));
    }
}
