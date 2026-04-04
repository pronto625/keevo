package com.keevo.reporting.report.application.service;

import com.keevo.messaging.whatsapp.domain.port.out.WhatsAppPort;
import com.keevo.identity.onboarding.domain.model.ReportChannel;
import com.keevo.reporting.report.domain.model.DeliveryStatus;
import com.keevo.reporting.report.domain.model.EndOfDayReport;
import com.keevo.reporting.report.domain.model.EndOfDayReportData;
import com.keevo.reporting.report.domain.model.ReportType;
import com.keevo.reporting.report.domain.port.in.GenerateEndOfDayReportUseCase.GenerateReportCommand;
import com.keevo.reporting.report.domain.port.out.EndOfDayReportRepository;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * AbstractReportGeneratorTest — TDD GREEN tests for Template Method skeleton.
 * Story 7.2 — Task 1.1b.
 */
@ExtendWith(MockitoExtension.class)
class AbstractReportGeneratorTest {

    @Mock private EndOfDayReportRepository reportRepository;
    @Mock private WhatsAppPort whatsAppPort;
    @Mock private MultiStoreSummaryService multiStoreSummaryService;

    private TestableReportGenerator generator;
    private GenerateReportCommand command;

    // ── Concrete test stub ────────────────────────────────────────────────────

    /** Minimal concrete subclass exposing call tracking. */
    static class TestableReportGenerator extends AbstractReportGenerator {

        private final EndOfDayReportData dataToReturn;
        boolean collectDataCalled = false;
        boolean formatContentCalled = false;

        TestableReportGenerator(EndOfDayReportRepository repo, WhatsAppPort wp,
                                 MultiStoreSummaryService ms, EndOfDayReportData data) {
            super(repo, wp, ms);
            this.dataToReturn = data;
        }

        @Override
        protected EndOfDayReportData collectData(GenerateReportCommand cmd) {
            collectDataCalled = true;
            return dataToReturn;
        }

        @Override
        protected String formatContent(EndOfDayReportData data) {
            formatContentCalled = true;
            return "Formatted content for " + data.storeName();
        }

        @Override
        protected ReportType getReportType() { return ReportType.DAILY; }

        @Override
        protected String resolveOwnerPhone(GenerateReportCommand cmd) { return "+243999000000"; }
    }

    @BeforeEach
    void setUp() {
        var data = new EndOfDayReportData(
                "Boutique Test", LocalDate.now(), LocalTime.of(20, 0), false,
                false,
                10, 150000, 100000, 50000, 15000,
                List.of(new EndOfDayReportData.TopProductEntry("Produit A", 5, 50000)),
                List.of(new EndOfDayReportData.EmployeeEntry("Alice", 7, 100000)),
                2, 1, 5000
        );
        generator = new TestableReportGenerator(reportRepository, whatsAppPort, multiStoreSummaryService, data);
        command = new GenerateReportCommand(
                UUID.randomUUID(), UUID.randomUUID(), "kv_test01", false, Instant.now(), null
        );
    }

    @Test
    void generateReport_shouldCallCollectData() {
        generator.generateReport(command);
        assertThat(generator.collectDataCalled).isTrue();
    }

    @Test
    void generateReport_shouldCallFormatContent() {
        generator.generateReport(command);
        assertThat(generator.formatContentCalled).isTrue();
    }

    @Test
    void generateReport_shouldPersistReport() {
        generator.generateReport(command);
        verify(reportRepository, atLeast(1)).save(any(EndOfDayReport.class));
    }

    @Test
    void generateReport_shouldDeliverViaWhatsApp() {
        generator.generateReport(command);
        verify(whatsAppPort, times(1)).sendReport(eq("+243999000000"), anyString());
    }

    @Test
    void generateReport_whenDeliverySucceeds_shouldMarkSent() {
        // whatsAppPort does not throw → delivery succeeds
        ArgumentCaptor<EndOfDayReport> captor = ArgumentCaptor.forClass(EndOfDayReport.class);
        generator.generateReport(command);
        verify(reportRepository, atLeast(2)).save(captor.capture());
        EndOfDayReport lastSaved = captor.getAllValues().get(captor.getAllValues().size() - 1);
        assertThat(lastSaved.getDeliveryStatus()).isEqualTo(DeliveryStatus.SENT);
    }

    @Test
    void generateReport_whenDeliveryFails_shouldMarkFailed() {
        doThrow(new RuntimeException("WhatsApp error")).when(whatsAppPort).sendReport(anyString(), anyString());
        ArgumentCaptor<EndOfDayReport> captor = ArgumentCaptor.forClass(EndOfDayReport.class);
        generator.generateReport(command);
        verify(reportRepository, atLeast(2)).save(captor.capture());
        EndOfDayReport lastSaved = captor.getAllValues().get(captor.getAllValues().size() - 1);
        assertThat(lastSaved.getDeliveryStatus()).isEqualTo(DeliveryStatus.FAILED);
    }

    @Test
    void generateReport_shouldCheckMultiStoreCondition() {
        generator.generateReport(command);
        verify(multiStoreSummaryService, times(1)).checkAndGenerateCombinedSummary(command);
    }

    @Test
    void generateReport_whenMultiStoreFails_shouldNotPropagateException() {
        doThrow(new RuntimeException("multi-store error"))
                .when(multiStoreSummaryService).checkAndGenerateCombinedSummary(any());
        // Should not throw
        generator.generateReport(command);
    }

    // ── Story 7.5: Channel strategy tests ────────────────────────────────────

    @Test
    void deliverReport_withInAppOnlyChannel_doesNotCallWhatsApp() {
        // Given — command with IN_APP_ONLY delivery channel
        GenerateReportCommand inAppCommand = new GenerateReportCommand(
                UUID.randomUUID(), UUID.randomUUID(), "kv_test01", false, Instant.now(), null,
                ReportChannel.IN_APP_ONLY
        );

        // When
        generator.generateReport(inAppCommand);

        // Then — WhatsApp MUST NOT be called
        verify(whatsAppPort, never()).sendReport(anyString(), anyString());
        verify(reportRepository, atLeast(1)).save(any(EndOfDayReport.class));
    }

    @Test
    void deliverReport_withWhatsAppChannel_callsPort() {
        // Given — command with explicit WHATSAPP channel
        GenerateReportCommand whatsAppCommand = new GenerateReportCommand(
                UUID.randomUUID(), UUID.randomUUID(), "kv_test01", false, Instant.now(), null,
                ReportChannel.WHATSAPP
        );

        // When
        generator.generateReport(whatsAppCommand);

        // Then — WhatsApp MUST be called exactly once
        verify(whatsAppPort, times(1)).sendReport(eq("+243999000000"), anyString());
    }

    @Test
    void deliverReport_withNullChannel_defaultsToWhatsApp() {
        // Given — command with null channel (backward compat — 6-arg constructor)
        GenerateReportCommand nullChannelCommand = new GenerateReportCommand(
                UUID.randomUUID(), UUID.randomUUID(), "kv_test01", false, Instant.now(), null
        );

        // When
        generator.generateReport(nullChannelCommand);

        // Then — should default to WhatsApp delivery
        verify(whatsAppPort, times(1)).sendReport(eq("+243999000000"), anyString());
    }
}
