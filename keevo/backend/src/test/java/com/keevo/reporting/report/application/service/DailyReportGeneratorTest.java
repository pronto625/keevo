package com.keevo.reporting.report.application.service;

import com.keevo.messaging.whatsapp.domain.port.out.WhatsAppPort;
import com.keevo.identity.auth.domain.model.User;
import com.keevo.identity.auth.domain.port.out.UserRepository;
import com.keevo.reporting.report.domain.model.DeliveryStatus;
import com.keevo.reporting.report.domain.model.EndOfDayReport;
import com.keevo.reporting.report.domain.model.EndOfDayReportData;
import com.keevo.reporting.report.domain.model.ReportType;
import com.keevo.reporting.report.domain.port.in.GenerateEndOfDayReportUseCase.GenerateReportCommand;
import com.keevo.reporting.report.domain.port.out.EndOfDayReportRepository;
import com.keevo.store.store.domain.model.Store;
import com.keevo.store.store.domain.model.StoreType;
import com.keevo.store.store.domain.port.out.StoreRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * DailyReportGeneratorTest — TDD GREEN tests for the concrete daily report generator.
 * Story 7.2 — Task 2.1.
 */
@ExtendWith(MockitoExtension.class)
class DailyReportGeneratorTest {

    @Mock private EndOfDayReportRepository reportRepository;
    @Mock private WhatsAppPort whatsAppPort;
    @Mock private MultiStoreSummaryService multiStoreSummaryService;
    @Mock private EndOfDayReportBuilder builder;
    @Mock private DailyReportFormatter formatter;
    @Mock private StoreRepository storeRepository;
    @Mock private UserRepository userRepository;

    private DailyReportGenerator generator;
    private UUID storeId;
    private UUID actorId;
    private GenerateReportCommand command;

    @BeforeEach
    void setUp() {
        generator = new DailyReportGenerator(
                reportRepository, whatsAppPort, multiStoreSummaryService,
                builder, formatter, storeRepository, userRepository);
        storeId = UUID.randomUUID();
        actorId = UUID.randomUUID();
        command = new GenerateReportCommand(storeId, null, "kv_test01", false, Instant.now(), null);
    }

    private EndOfDayReportData sampleData() {
        return new EndOfDayReportData(
                "Boutique Test", LocalDate.now(), LocalTime.of(20, 0), false,
                false,
                5, 100000, 70000, 30000, 20000,
                List.of(), List.of(), 1, 0, 0
        );
    }

    @Test
    void generateReport_shouldReturnDailyType() {
        when(storeRepository.findById(storeId)).thenReturn(Optional.of(
                new Store(storeId, "Boutique Test", StoreType.STORE, null, null, true, Instant.now(), Instant.now())));
        when(builder.build(any(), any(), any(), anyString(), any(), any(), anyBoolean())).thenReturn(sampleData());
        when(formatter.format(any())).thenReturn("Rapport WhatsApp");
        when(userRepository.findOwnerByTenantSchemaName("kv_test01")).thenReturn(Optional.empty());

        EndOfDayReport report = generator.generateReport(command);
        assertThat(report.getReportType()).isEqualTo(ReportType.DAILY);
    }

    @Test
    void generateReport_whenStoreNotFound_shouldUseFallbackName() {
        when(storeRepository.findById(storeId)).thenReturn(Optional.empty());
        when(builder.build(any(), any(), any(), anyString(), any(), any(), anyBoolean())).thenReturn(sampleData());
        when(formatter.format(any())).thenReturn("Rapport");
        when(userRepository.findOwnerByTenantSchemaName(anyString())).thenReturn(Optional.empty());

        // Should not throw
        generator.generateReport(command);
        verify(builder, times(1)).build(eq(storeId), any(), any(), anyString(), any(), any(), eq(false));
    }

    @Test
    void generateReport_shouldUseOwnerPhoneFromRepository() {
        User owner = mock(User.class);
        when(owner.getPhoneNumber()).thenReturn("+243812345678");
        when(storeRepository.findById(storeId)).thenReturn(Optional.empty());
        when(builder.build(any(), any(), any(), anyString(), any(), any(), anyBoolean())).thenReturn(sampleData());
        when(formatter.format(any())).thenReturn("Rapport");
        when(userRepository.findOwnerByTenantSchemaName("kv_test01")).thenReturn(Optional.of(owner));

        generator.generateReport(command);
        verify(whatsAppPort, times(1)).sendReport(eq("+243812345678"), anyString());
    }

    @Test
    void generateReport_whenOwnerPhoneAbsent_shouldMarkInAppOnly() {
        when(storeRepository.findById(storeId)).thenReturn(Optional.empty());
        when(builder.build(any(), any(), any(), anyString(), any(), any(), anyBoolean())).thenReturn(sampleData());
        when(formatter.format(any())).thenReturn("Rapport");
        when(userRepository.findOwnerByTenantSchemaName(anyString())).thenReturn(Optional.empty());

        EndOfDayReport report = generator.generateReport(command);
        assertThat(report.getDeliveryStatus()).isEqualTo(DeliveryStatus.IN_APP_ONLY);
        verifyNoInteractions(whatsAppPort);
    }

    @Test
    void generateReport_shouldPersistWithTenantId() {
        when(storeRepository.findById(storeId)).thenReturn(Optional.empty());
        when(builder.build(any(), any(), any(), anyString(), any(), any(), anyBoolean())).thenReturn(sampleData());
        when(formatter.format(any())).thenReturn("Rapport");
        when(userRepository.findOwnerByTenantSchemaName(anyString())).thenReturn(Optional.empty());

        EndOfDayReport report = generator.generateReport(command);
        assertThat(report.getTenantId()).isEqualTo("kv_test01");
    }

    @Test
    void generateReport_deliverySuccess_shouldMarkSent() {
        User owner = mock(User.class);
        when(owner.getPhoneNumber()).thenReturn("+243812345678");
        when(storeRepository.findById(storeId)).thenReturn(Optional.empty());
        when(builder.build(any(), any(), any(), anyString(), any(), any(), anyBoolean())).thenReturn(sampleData());
        when(formatter.format(any())).thenReturn("Rapport");
        when(userRepository.findOwnerByTenantSchemaName(anyString())).thenReturn(Optional.of(owner));

        EndOfDayReport report = generator.generateReport(command);
        assertThat(report.getDeliveryStatus()).isEqualTo(DeliveryStatus.SENT);
    }

    @Test
    void generateReport_deliveryFailure_shouldMarkFailed() {
        User owner = mock(User.class);
        when(owner.getPhoneNumber()).thenReturn("+243812345678");
        when(storeRepository.findById(storeId)).thenReturn(Optional.empty());
        when(builder.build(any(), any(), any(), anyString(), any(), any(), anyBoolean())).thenReturn(sampleData());
        when(formatter.format(any())).thenReturn("Rapport");
        when(userRepository.findOwnerByTenantSchemaName(anyString())).thenReturn(Optional.of(owner));
        doThrow(new RuntimeException("net error")).when(whatsAppPort).sendReport(anyString(), anyString());

        EndOfDayReport report = generator.generateReport(command);
        assertThat(report.getDeliveryStatus()).isEqualTo(DeliveryStatus.FAILED);
    }
}
