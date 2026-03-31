package com.keevo.reporting.report.application.service;

import com.keevo.commerce.sale.domain.port.out.WhatsAppPort;
import com.keevo.identity.auth.domain.port.out.UserRepository;
import com.keevo.reporting.report.domain.model.DeliveryStatus;
import com.keevo.reporting.report.domain.model.EndOfDayReport;
import com.keevo.reporting.report.domain.model.ReportType;
import com.keevo.reporting.report.domain.port.in.GenerateEndOfDayReportUseCase.GenerateReportCommand;
import com.keevo.reporting.report.domain.port.out.EndOfDayReportRepository;
import com.keevo.store.store.domain.port.out.StoreRepository;
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
 * MultiStoreSummaryServiceTest — TDD GREEN tests for combined multi-store report logic.
 * Story 7.2 — Task 2.4.
 */
@ExtendWith(MockitoExtension.class)
class MultiStoreSummaryServiceTest {

    @Mock private EndOfDayReportRepository reportRepository;
    @Mock private StoreRepository storeRepository;
    @Mock private WhatsAppPort whatsAppPort;
    @Mock private DailyReportFormatter formatter;
    @Mock private UserRepository userRepository;

    private MultiStoreSummaryService service;
    private GenerateReportCommand command;

    @BeforeEach
    void setUp() {
        service = new MultiStoreSummaryService(reportRepository, storeRepository,
                whatsAppPort, formatter, userRepository);
        command = new GenerateReportCommand(UUID.randomUUID(), UUID.randomUUID(),
                "kv_test01", false, Instant.now());
    }

    private EndOfDayReport stubReport(UUID storeId, String name, int revenue, int sales) {
        return EndOfDayReport.createNew("kv_test01", storeId, name,
                ReportType.DAILY, LocalDate.now(), "Contenu", revenue, sales, false);
    }

    @Test
    void checkAndGenerate_whenSingleStore_shouldDoNothing() {
        when(storeRepository.countActive()).thenReturn(1);
        service.checkAndGenerateCombinedSummary(command);
        verify(reportRepository, never()).allStoresClosedForDate(anyString(), any(), anyInt());
    }

    @Test
    void checkAndGenerate_whenNotAllStoresClosed_shouldDoNothing() {
        when(storeRepository.countActive()).thenReturn(3);
        when(reportRepository.allStoresClosedForDate(anyString(), any(), eq(3))).thenReturn(false);
        service.checkAndGenerateCombinedSummary(command);
        verify(reportRepository, never()).save(any());
    }

    @Test
    void checkAndGenerate_whenCombinedAlreadyExists_shouldDoNothing() {
        when(storeRepository.countActive()).thenReturn(2);
        when(reportRepository.allStoresClosedForDate(anyString(), any(), eq(2))).thenReturn(true);
        EndOfDayReport existing = stubReport(UUID.randomUUID(), "Toutes boutiques", 0, 0);
        when(reportRepository.findByDateAndTenant(any(), anyString(), eq(ReportType.DAILY_COMBINED)))
                .thenReturn(List.of(existing));
        service.checkAndGenerateCombinedSummary(command);
        verify(reportRepository, never()).save(any());
    }

    @Test
    void checkAndGenerate_whenAllStoresClosed_shouldSaveCombinedReport() {
        when(storeRepository.countActive()).thenReturn(2);
        when(reportRepository.allStoresClosedForDate(anyString(), any(), eq(2))).thenReturn(true);
        when(reportRepository.findByDateAndTenant(any(), anyString(), eq(ReportType.DAILY_COMBINED)))
                .thenReturn(List.of());
        when(reportRepository.findByDateAndTenant(any(), anyString(), eq(ReportType.DAILY)))
                .thenReturn(List.of(
                        stubReport(UUID.randomUUID(), "Boutique A", 100000, 5),
                        stubReport(UUID.randomUUID(), "Boutique B", 80000, 4)
                ));
        when(formatter.formatCombinedSummary(anyString(), any(), anyInt(), anyInt())).thenReturn("Résumé combiné");
        when(userRepository.findOwnerByTenantSchemaName(anyString())).thenReturn(Optional.empty());

        service.checkAndGenerateCombinedSummary(command);

        verify(reportRepository, atLeast(1)).save(argThat(r ->
                r.getReportType() == ReportType.DAILY_COMBINED));
    }

    @Test
    void checkAndGenerate_whenAllStoresClosed_shouldSendWhatsApp() {
        when(storeRepository.countActive()).thenReturn(2);
        when(reportRepository.allStoresClosedForDate(anyString(), any(), eq(2))).thenReturn(true);
        when(reportRepository.findByDateAndTenant(any(), anyString(), eq(ReportType.DAILY_COMBINED)))
                .thenReturn(List.of());
        when(reportRepository.findByDateAndTenant(any(), anyString(), eq(ReportType.DAILY)))
                .thenReturn(List.of(stubReport(UUID.randomUUID(), "A", 50000, 3)));
        when(formatter.formatCombinedSummary(anyString(), any(), anyInt(), anyInt())).thenReturn("Résumé");
        when(userRepository.findOwnerByTenantSchemaName(anyString())).thenReturn(Optional.empty());

        service.checkAndGenerateCombinedSummary(command);

        verify(whatsAppPort, times(1)).sendReport(anyString(), anyString());
    }

    @Test
    void checkAndGenerate_whenDeliveryFails_shouldMarkFailed() {
        when(storeRepository.countActive()).thenReturn(2);
        when(reportRepository.allStoresClosedForDate(anyString(), any(), eq(2))).thenReturn(true);
        when(reportRepository.findByDateAndTenant(any(), anyString(), eq(ReportType.DAILY_COMBINED)))
                .thenReturn(List.of());
        when(reportRepository.findByDateAndTenant(any(), anyString(), eq(ReportType.DAILY)))
                .thenReturn(List.of(stubReport(UUID.randomUUID(), "A", 50000, 3)));
        when(formatter.formatCombinedSummary(anyString(), any(), anyInt(), anyInt())).thenReturn("Résumé");
        when(userRepository.findOwnerByTenantSchemaName(anyString())).thenReturn(Optional.empty());
        doThrow(new RuntimeException("WhatsApp error")).when(whatsAppPort).sendReport(anyString(), anyString());

        service.checkAndGenerateCombinedSummary(command);

        // Verify the combined report was saved twice (once before delivery, once after failure)
        verify(reportRepository, times(2)).save(argThat(r ->
                r.getReportType() == ReportType.DAILY_COMBINED));
    }
}
