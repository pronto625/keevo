package com.keevo.reporting.report.application.service;

import com.keevo.messaging.whatsapp.domain.port.out.WhatsAppPort;
import com.keevo.identity.auth.domain.port.out.OwnerRecipientResolver;
import com.keevo.identity.auth.adapter.out.persistence.impl.OwnerRecipientResolverAdapter;
import com.keevo.identity.auth.domain.model.Role;
import com.keevo.identity.auth.domain.model.User;
import com.keevo.identity.auth.domain.port.out.UserRepository;
import com.keevo.identity.onboarding.domain.model.ReportChannel;
import com.keevo.reporting.report.domain.model.DeliveryStatus;
import com.keevo.reporting.report.domain.model.EndOfDayReport;
import com.keevo.reporting.report.domain.model.EndOfDayReportData;
import com.keevo.reporting.report.domain.model.ReportType;
import com.keevo.reporting.report.domain.model.WeeklyReportData;
import com.keevo.reporting.report.domain.port.in.GenerateWeeklyReportUseCase;
import com.keevo.reporting.report.domain.port.in.GenerateWeeklyReportUseCase.WeeklyReportCommand;
import com.keevo.reporting.report.domain.port.out.EndOfDayReportRepository;
import com.keevo.store.store.domain.model.Store;
import com.keevo.store.store.domain.model.StoreType;
import com.keevo.store.store.domain.port.out.StoreRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
 * WeeklyReportGeneratorTest — TDD RED tests for the concrete weekly report generator.
 * Story 7.3 — Task 4.1.
 *
 * Mirrors DailyReportGeneratorTest — same pattern with WeeklyReportBuilder + WeeklyReportFormatter.
 */
@ExtendWith(MockitoExtension.class)
class WeeklyReportGeneratorTest {

    @Mock private EndOfDayReportRepository reportRepository;
    @Mock private WhatsAppPort whatsAppPort;
    @Mock private MultiStoreSummaryService multiStoreSummaryService;
    @Mock private WeeklyReportBuilder builder;
    @Mock private WeeklyReportFormatter formatter;
    @Mock private StoreRepository storeRepository;
    @Mock private OwnerRecipientResolver ownerRecipientResolver;

    private WeeklyReportGenerator generator;
    private UUID storeId;
    private WeeklyReportCommand command;

    private static final LocalDate MONDAY = LocalDate.of(2026, 3, 23);
    private static final LocalDate SUNDAY = LocalDate.of(2026, 3, 29);

    @BeforeEach
    void setUp() {
        generator = new WeeklyReportGenerator(
                reportRepository, whatsAppPort, multiStoreSummaryService,
                builder, formatter, storeRepository, ownerRecipientResolver);
        storeId = UUID.randomUUID();
        command = new WeeklyReportCommand(
                storeId, "kv_test01", true,
                Instant.parse("2026-03-23T00:00:00Z"),
                Instant.parse("2026-03-29T22:59:59Z"),
                null  // WHATSAPP default
        );
    }

    private WeeklyReportData sampleWeeklyData() {
        return new WeeklyReportData(
                "Boutique Test", MONDAY, SUNDAY,
                10, 200_000, 120_000, 80_000, 20_000,
                List.of(), List.of(), List.of(),
                150_000, 50_000, 2, true
        );
    }

    private EndOfDayReportData sampleEodData() {
        return new EndOfDayReportData(
                "Boutique Test", SUNDAY, LocalTime.of(23, 59), true, false,
                10, 200_000, 120_000, 80_000, 20_000,
                List.of(), List.of(), 2, 0, 0, null
        );
    }

    @Test
    void generateWeeklyReport_buildsAndFormatsWeeklyData() {
        when(storeRepository.findById(storeId)).thenReturn(Optional.of(
                new Store(storeId, "Boutique Test", StoreType.STORE, null, null, true, Instant.now(), Instant.now())));
        when(builder.build(any(), any(), anyString(), any(), anyString(), anyBoolean()))
                .thenReturn(sampleWeeklyData());
        when(formatter.format(any(WeeklyReportData.class))).thenReturn("📅 Rapport Hebdomadaire");
        when(ownerRecipientResolver.resolveOwnerWhatsAppPhone("kv_test01")).thenReturn(Optional.empty());

        generator.generateWeeklyReport(command);

        verify(builder).build(eq(storeId), any(), anyString(), any(), eq("kv_test01"), eq(true));
        verify(formatter).format(any(WeeklyReportData.class));
    }

    @Test
    void generateWeeklyReport_persistsWithReportTypeWEEKLY() {
        when(storeRepository.findById(storeId)).thenReturn(Optional.empty());
        when(builder.build(any(), any(), anyString(), any(), anyString(), anyBoolean()))
                .thenReturn(sampleWeeklyData());
        when(formatter.format(any(WeeklyReportData.class))).thenReturn("📅 Rapport");
        when(ownerRecipientResolver.resolveOwnerWhatsAppPhone(anyString())).thenReturn(Optional.empty());

        EndOfDayReport report = generator.generateWeeklyReport(command);

        assertThat(report.getReportType()).isEqualTo(ReportType.WEEKLY);
    }

    @Test
    void generateWeeklyReport_callsWhatsApp_onWhatsAppChannel() {
        when(storeRepository.findById(storeId)).thenReturn(Optional.empty());
        when(builder.build(any(), any(), anyString(), any(), anyString(), anyBoolean()))
                .thenReturn(sampleWeeklyData());
        when(formatter.format(any(WeeklyReportData.class))).thenReturn("📅 Rapport");
        when(ownerRecipientResolver.resolveOwnerWhatsAppPhone("kv_test01")).thenReturn(Optional.of("+237600072001"));

        EndOfDayReport report = generator.generateWeeklyReport(command);

        verify(whatsAppPort).sendReport(eq("+237600072001"), anyString());
        assertThat(report.getDeliveryStatus()).isEqualTo(DeliveryStatus.SENT);
    }

    @Test
    void generateWeeklyReport_skipsWhatsApp_onInAppOnlyChannel() {
        WeeklyReportCommand inAppCommand = new WeeklyReportCommand(
                storeId, "kv_test01", true,
                command.weekStart(), command.weekEnd(),
                ReportChannel.IN_APP_ONLY
        );
        when(storeRepository.findById(storeId)).thenReturn(Optional.empty());
        when(builder.build(any(), any(), anyString(), any(), anyString(), anyBoolean()))
                .thenReturn(sampleWeeklyData());
        when(formatter.format(any(WeeklyReportData.class))).thenReturn("📅 Rapport");

        EndOfDayReport report = generator.generateWeeklyReport(inAppCommand);

        verifyNoInteractions(whatsAppPort);
        assertThat(report.getDeliveryStatus()).isEqualTo(DeliveryStatus.IN_APP_ONLY);
    }

    @Test
    void generateWeeklyReport_whatsAppFails_setsStatusFailed() {
        when(storeRepository.findById(storeId)).thenReturn(Optional.empty());
        when(builder.build(any(), any(), anyString(), any(), anyString(), anyBoolean()))
                .thenReturn(sampleWeeklyData());
        when(formatter.format(any(WeeklyReportData.class))).thenReturn("📅 Rapport");
        when(ownerRecipientResolver.resolveOwnerWhatsAppPhone("kv_test01")).thenReturn(Optional.of("+237600072001"));
        doThrow(new RuntimeException("WhatsApp unreachable")).when(whatsAppPort).sendReport(anyString(), anyString());

        EndOfDayReport report = generator.generateWeeklyReport(command);

        assertThat(report.getDeliveryStatus()).isEqualTo(DeliveryStatus.FAILED);
    }

    @Test
    void generateWeeklyReport_getReportType_returnsWEEKLY() {
        when(storeRepository.findById(storeId)).thenReturn(Optional.empty());
        when(builder.build(any(), any(), anyString(), any(), anyString(), anyBoolean()))
                .thenReturn(sampleWeeklyData());
        when(formatter.format(any(WeeklyReportData.class))).thenReturn("Rapport");
        when(ownerRecipientResolver.resolveOwnerWhatsAppPhone(anyString())).thenReturn(Optional.empty());

        EndOfDayReport report = generator.generateWeeklyReport(command);

        assertThat(report.getReportType()).isEqualTo(ReportType.WEEKLY);
    }

    @Test
    void generateWeeklyReport_noOwnerPhone_marksInAppOnly() {
        when(storeRepository.findById(storeId)).thenReturn(Optional.empty());
        when(builder.build(any(), any(), anyString(), any(), anyString(), anyBoolean()))
                .thenReturn(sampleWeeklyData());
        when(formatter.format(any(WeeklyReportData.class))).thenReturn("Rapport");
        when(ownerRecipientResolver.resolveOwnerWhatsAppPhone(anyString())).thenReturn(Optional.empty());

        EndOfDayReport report = generator.generateWeeklyReport(command);

        verifyNoInteractions(whatsAppPort);
        assertThat(report.getDeliveryStatus()).isEqualTo(DeliveryStatus.IN_APP_ONLY);
    }

    // ── v1s-13-11 (D2): chained window ─────────────────────────────────────────

    @Test
    void generateWeeklyReport_passesChainedWindowToBuilder_andPersistsCreatedAtAsReceivedUntil() {
        Instant start = Instant.parse("2026-03-22T19:00:00.500001Z");
        Instant now = Instant.parse("2026-03-29T19:00:00.123456Z");   // Sunday 20:00 WAT
        Instant lateFrom = Instant.parse("2026-03-22T19:00:00.500Z");
        WeeklyReportCommand chained = new WeeklyReportCommand(
                storeId, "kv_test01", true, start, now, ReportChannel.IN_APP_ONLY, lateFrom, now);
        when(storeRepository.findById(storeId)).thenReturn(Optional.empty());
        when(builder.build(any(), any(), anyString(), any(), anyString(), anyBoolean()))
                .thenReturn(sampleWeeklyData());
        when(formatter.format(any(WeeklyReportData.class))).thenReturn("Rapport");

        EndOfDayReport report = generator.generateWeeklyReport(chained);

        var windowCaptor = org.mockito.ArgumentCaptor.forClass(
                com.keevo.commerce.sale.domain.model.ClosureWindow.class);
        verify(builder).build(eq(storeId), windowCaptor.capture(), anyString(), eq(SUNDAY),
                eq("kv_test01"), eq(true));
        assertThat(windowCaptor.getValue()).isEqualTo(
                new com.keevo.commerce.sale.domain.model.ClosureWindow(start, now, lateFrom, now));
        assertThat(report.getCreatedAt()).isEqualTo(now);
        var savedCaptor = org.mockito.ArgumentCaptor.forClass(EndOfDayReport.class);
        verify(reportRepository, atLeastOnce()).save(savedCaptor.capture());
        assertThat(savedCaptor.getAllValues()).allSatisfy(r -> assertThat(r.getCreatedAt()).isEqualTo(now));
    }

    @Test
    void generateWeeklyReport_plainCommand_usesNoLateSalesAndNoArrivalLimit() {
        when(storeRepository.findById(storeId)).thenReturn(Optional.empty());
        when(builder.build(any(), any(), anyString(), any(), anyString(), anyBoolean()))
                .thenReturn(sampleWeeklyData());
        when(formatter.format(any(WeeklyReportData.class))).thenReturn("Rapport");
        when(ownerRecipientResolver.resolveOwnerWhatsAppPhone(anyString())).thenReturn(Optional.empty());

        generator.generateWeeklyReport(command);

        var windowCaptor = org.mockito.ArgumentCaptor.forClass(
                com.keevo.commerce.sale.domain.model.ClosureWindow.class);
        verify(builder).build(eq(storeId), windowCaptor.capture(), anyString(), any(), anyString(), anyBoolean());
        assertThat(windowCaptor.getValue().lateFrom())
                .isEqualTo(com.keevo.commerce.sale.domain.model.ClosureWindow.NEVER);
        assertThat(windowCaptor.getValue().receivedUntil())
                .isEqualTo(com.keevo.commerce.sale.domain.model.ClosureWindow.NEVER);
    }

    /** v1s-14-17 — real resolver over a blank owner phone: IN_APP_ONLY, WhatsApp never called. */
    @Test
    void generateWeeklyReport_withRealResolver_blankOwnerPhone_marksInAppOnly_neverSends() {
        UserRepository userRepository = mock(UserRepository.class);
        when(userRepository.findOwnerByTenantSchemaName("kv_test01")).thenReturn(Optional.of(
                new User(UUID.randomUUID(), "   ", "hash", Role.OWNER, true, Instant.now())));
        WeeklyReportGenerator realResolverGenerator = new WeeklyReportGenerator(
                reportRepository, whatsAppPort, multiStoreSummaryService,
                builder, formatter, storeRepository, new OwnerRecipientResolverAdapter(userRepository));
        when(storeRepository.findById(storeId)).thenReturn(Optional.empty());
        when(builder.build(any(), any(), anyString(), any(), anyString(), anyBoolean()))
                .thenReturn(sampleWeeklyData());
        when(formatter.format(any(WeeklyReportData.class))).thenReturn("📅 Rapport");

        EndOfDayReport report = realResolverGenerator.generateWeeklyReport(command);

        assertThat(report.getDeliveryStatus()).isEqualTo(DeliveryStatus.IN_APP_ONLY);
        verify(whatsAppPort, never()).sendReport(anyString(), anyString());
    }
}
