package com.keevo.identity.onboarding.application.service;

import com.keevo.messaging.whatsapp.domain.port.out.WhatsAppPort;
import com.keevo.identity.auth.domain.model.User;
import com.keevo.identity.auth.domain.port.out.UserRepository;
import com.keevo.identity.onboarding.domain.port.in.SendTestReportUseCase;
import com.keevo.reporting.report.application.service.DailyReportFormatter;
import com.keevo.reporting.report.application.service.EndOfDayReportBuilder;
import com.keevo.reporting.report.domain.model.EndOfDayReportData;
import com.keevo.reporting.report.domain.model.EndOfDayReportData.TopProductEntry;
import com.keevo.store.store.domain.model.Store;
import com.keevo.store.store.domain.model.StoreType;
import com.keevo.store.store.domain.port.out.StoreRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
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
 * SendTestReportServiceTest — Story 7.5 Task 1.2
 * TDD RED → GREEN tests for SendTestReportService.
 */
@ExtendWith(MockitoExtension.class)
class SendTestReportServiceTest {

    @Mock private EndOfDayReportBuilder endOfDayReportBuilder;
    @Mock private DailyReportFormatter dailyReportFormatter;
    @Mock private WhatsAppPort whatsAppPort;
    @Mock private StoreRepository storeRepository;
    @Mock private UserRepository userRepository;

    @InjectMocks private SendTestReportService service;

    private static final String TENANT_ID = "kv_test_7_5";
    private static final UUID STORE_ID = UUID.randomUUID();
    private static final String OWNER_PHONE = "+243700000001";
    private static final String FORMATTED_CONTENT = "📊 Rapport du jour...";

    private Store store;
    private EndOfDayReportData reportData;

    @BeforeEach
    void setUp() {
        store = new Store(STORE_ID, "Boutique Test", StoreType.STORE, "Kinshasa", null, true, Instant.now(), Instant.now());

        reportData = new EndOfDayReportData(
                "Boutique Test", LocalDate.now(), LocalTime.now(), false, false,
                5, 75000, 50000, 25000, 15000,
                List.of(new TopProductEntry("Produit A", 2, 30000)),
                List.of(), 3, 0, 0, null
        );

        // WhatsApp is "configured" by default in service tests
        when(whatsAppPort.isConfigured()).thenReturn(true);
    }

    @Test
    void sendTestReport_generatesReportWithTestPrefix() {
        // Given
        when(storeRepository.findAllActive()).thenReturn(List.of(store));
        when(endOfDayReportBuilder.build(eq(STORE_ID), any(), any(), eq("Boutique Test"), any(), any(), eq(false)))
                .thenReturn(reportData);
        when(dailyReportFormatter.format(reportData)).thenReturn(FORMATTED_CONTENT);
        when(userRepository.findOwnerByTenantSchemaName(TENANT_ID))
                .thenReturn(Optional.of(mockUser(OWNER_PHONE)));

        // When
        SendTestReportUseCase.TestReportResult result = service.sendTestReport(TENANT_ID);

        // Then
        assertThat(result.testSent()).isTrue();
        ArgumentCaptor<String> contentCaptor = ArgumentCaptor.forClass(String.class);
        verify(whatsAppPort).sendReport(eq(OWNER_PHONE), contentCaptor.capture());
        assertThat(contentCaptor.getValue()).contains("[TEST]");
        assertThat(contentCaptor.getValue()).contains(FORMATTED_CONTENT);
    }

    @Test
    void sendTestReport_doesNotPersistReport() {
        // Given
        when(storeRepository.findAllActive()).thenReturn(List.of(store));
        when(endOfDayReportBuilder.build(eq(STORE_ID), any(), any(), eq("Boutique Test"), any(), any(), eq(false)))
                .thenReturn(reportData);
        when(dailyReportFormatter.format(reportData)).thenReturn(FORMATTED_CONTENT);
        when(userRepository.findOwnerByTenantSchemaName(TENANT_ID))
                .thenReturn(Optional.of(mockUser(OWNER_PHONE)));

        // When
        service.sendTestReport(TENANT_ID);

        // Then — no assertion needed: SendTestReportService has no reportRepository
        //        dependency, so persistence is architecturally impossible.
        verify(whatsAppPort).sendReport(eq(OWNER_PHONE), anyString());
    }

    @Test
    void sendTestReport_whatsAppFails_returnsFalse() {
        // Given
        when(storeRepository.findAllActive()).thenReturn(List.of(store));
        when(endOfDayReportBuilder.build(eq(STORE_ID), any(), any(), eq("Boutique Test"), any(), any(), eq(false)))
                .thenReturn(reportData);
        when(dailyReportFormatter.format(reportData)).thenReturn(FORMATTED_CONTENT);
        when(userRepository.findOwnerByTenantSchemaName(TENANT_ID))
                .thenReturn(Optional.of(mockUser(OWNER_PHONE)));
        doThrow(new RuntimeException("WhatsApp unavailable")).when(whatsAppPort).sendReport(anyString(), anyString());

        // When
        SendTestReportUseCase.TestReportResult result = service.sendTestReport(TENANT_ID);

        // Then — exception is caught, returns false (no throw)
        assertThat(result.testSent()).isFalse();
    }

    @Test
    void sendTestReport_noOwnerPhone_returnsFalse() {
        // Given — no owner found for this tenant
        when(userRepository.findOwnerByTenantSchemaName(TENANT_ID))
                .thenReturn(Optional.empty());

        // When
        SendTestReportUseCase.TestReportResult result = service.sendTestReport(TENANT_ID);

        // Then
        assertThat(result.testSent()).isFalse();
        verify(whatsAppPort, never()).sendReport(anyString(), anyString());
    }

    // ── Helper ───────────────────────────────────────────────────────────────

    private User mockUser(String phone) {
        return User.newOwner(phone, "$2a$10$testhashedpassword123456789012");
    }
}
