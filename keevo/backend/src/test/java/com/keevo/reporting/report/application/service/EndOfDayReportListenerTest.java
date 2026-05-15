package com.keevo.reporting.report.application.service;

import com.keevo.commerce.sale.domain.model.DayClosedEvent;
import com.keevo.commerce.sale.domain.model.DayClosureSummary;
import com.keevo.identity.onboarding.domain.model.ReportChannel;
import com.keevo.identity.onboarding.domain.model.TenantPreferences;
import com.keevo.identity.onboarding.domain.port.out.TenantPreferencesRepository;
import com.keevo.reporting.report.application.service.EndOfDayReportBuilder;
import com.keevo.reporting.report.domain.port.in.GenerateEndOfDayReportUseCase;
import com.keevo.reporting.report.domain.port.in.GenerateEndOfDayReportUseCase.GenerateReportCommand;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * EndOfDayReportListenerTest — TDD GREEN tests for the DayClosedEvent observer.
 * Story 7.2 — Task 2.2.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EndOfDayReportListenerTest {

    @Mock private GenerateEndOfDayReportUseCase generateReportUseCase;
    @Mock private EndOfDayReportBuilder builder;
    @Mock private TenantPreferencesRepository tenantPreferencesRepository;

    private EndOfDayReportListener listener;
    private UUID storeId;
    private UUID actorId;
    private UUID closureId;

    @BeforeEach
    void setUp() {
        listener = new EndOfDayReportListener(generateReportUseCase, builder, tenantPreferencesRepository);
        // Default: EOD report is enabled with WHATSAPP channel
        when(tenantPreferencesRepository.findByCurrentTenant())
                .thenReturn(java.util.Optional.of(enabledPrefs()));
        // Default: no employees have sales in the window
        when(builder.getDistinctEmployeeIds(any(), any(), any())).thenReturn(List.of());
        storeId = UUID.randomUUID();
        actorId = UUID.randomUUID();
        closureId = UUID.randomUUID();
    }

    private DayClosedEvent buildEvent(boolean isAutomatic) {
        var summary = new DayClosureSummary(5, 100000, null, null, 0, 0, 0, 0, 0);
        return new DayClosedEvent(closureId, storeId, actorId, summary, isAutomatic,
                "kv_test01", Instant.now(), Instant.EPOCH, Instant.now());
    }

    @Test
    void onDayClosed_shouldCallGenerateReport() {
        listener.onDayClosed(buildEvent(false));
        verify(generateReportUseCase, times(1)).generateReport(any(GenerateReportCommand.class));
    }

    @Test
    void onDayClosed_shouldPassCorrectTenantId() {
        ArgumentCaptor<GenerateReportCommand> captor = ArgumentCaptor.forClass(GenerateReportCommand.class);
        listener.onDayClosed(buildEvent(false));
        verify(generateReportUseCase).generateReport(captor.capture());
        assertThat(captor.getValue().tenantId()).isEqualTo("kv_test01");
    }

    @Test
    void onDayClosed_shouldPassCorrectStoreId() {
        ArgumentCaptor<GenerateReportCommand> captor = ArgumentCaptor.forClass(GenerateReportCommand.class);
        listener.onDayClosed(buildEvent(false));
        verify(generateReportUseCase).generateReport(captor.capture());
        assertThat(captor.getValue().storeId()).isEqualTo(storeId);
    }

    @Test
    void onDayClosed_shouldPassIsAutomatic() {
        ArgumentCaptor<GenerateReportCommand> captor = ArgumentCaptor.forClass(GenerateReportCommand.class);
        listener.onDayClosed(buildEvent(true));
        verify(generateReportUseCase).generateReport(captor.capture());
        assertThat(captor.getValue().isAutomatic()).isTrue();
    }

    @Test
    void onDayClosed_whenGenerateThrows_shouldNotPropagateException() {
        doThrow(new RuntimeException("DB error")).when(generateReportUseCase).generateReport(any());
        // Must not throw — listener handles errors internally
        listener.onDayClosed(buildEvent(false));
    }

    private TenantPreferences enabledPrefs() {
        return TenantPreferences.withDefaults(UUID.randomUUID(), null, "20:00:00", true, Instant.now());
    }
}
