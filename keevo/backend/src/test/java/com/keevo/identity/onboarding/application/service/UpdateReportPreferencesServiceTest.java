package com.keevo.identity.onboarding.application.service;

import com.keevo.identity.onboarding.domain.model.PreferenceUpdatedEvent;
import com.keevo.identity.onboarding.domain.model.ReportChannel;
import com.keevo.identity.onboarding.domain.model.StockAlertChannel;
import com.keevo.identity.onboarding.domain.model.TenantPreferences;
import com.keevo.identity.onboarding.domain.model.SectorType;
import com.keevo.identity.onboarding.domain.port.in.UpdateReportPreferencesCommand;
import com.keevo.identity.onboarding.domain.port.out.TenantPreferencesRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * UpdateReportPreferencesServiceTest — Story 7.5 Task 1.1
 * TDD RED → GREEN tests for UpdateReportPreferencesService.
 */
@ExtendWith(MockitoExtension.class)
class UpdateReportPreferencesServiceTest {

    @Mock private TenantPreferencesRepository tenantPreferencesRepository;
    @Mock private ApplicationEventPublisher eventPublisher;

    @InjectMocks private UpdateReportPreferencesService service;

    private static final String TENANT_ID = "kv_test_7_5";
    private TenantPreferences existingPrefs;

    @BeforeEach
    void setUp() {
        existingPrefs = new TenantPreferences(
                UUID.randomUUID(), SectorType.OTHER, "20:00:00", true, Instant.now(),
                true, ReportChannel.WHATSAPP, true, 0, "20:00:00", ReportChannel.WHATSAPP,
                true, ReportChannel.WHATSAPP, StockAlertChannel.PUSH,
                true
        );
    }

    @Test
    void updatePreferences_withValidCommand_persistsAllFields() {
        // Given
        when(tenantPreferencesRepository.findByCurrentTenant()).thenReturn(Optional.of(existingPrefs));
        when(tenantPreferencesRepository.update(any())).thenAnswer(inv -> inv.getArgument(0));

        UpdateReportPreferencesCommand cmd = new UpdateReportPreferencesCommand(
                false, ReportChannel.IN_APP_ONLY, "22:00:00",
                true, 5, "19:00:00", ReportChannel.WHATSAPP,
                false, ReportChannel.IN_APP_ONLY,
                true, StockAlertChannel.BOTH,
                null
        );

        try (MockedStatic<TenantContext> ctx = mockStatic(TenantContext.class)) {
            ctx.when(TenantContext::getCurrentTenant).thenReturn(TENANT_ID);

            // When
            TenantPreferences result = service.update(cmd);

            // Then
            ArgumentCaptor<TenantPreferences> captor = ArgumentCaptor.forClass(TenantPreferences.class);
            verify(tenantPreferencesRepository).update(captor.capture());
            TenantPreferences saved = captor.getValue();

            assertThat(saved.eodReportEnabled()).isFalse();
            assertThat(saved.eodReportChannel()).isEqualTo(ReportChannel.IN_APP_ONLY);
            assertThat(saved.eodReportTime()).isEqualTo("22:00:00");
            assertThat(saved.weeklyReportEnabled()).isTrue();
            assertThat(saved.weeklyReportDay()).isEqualTo(5);
            assertThat(saved.weeklyReportTime()).isEqualTo("19:00:00");
            assertThat(saved.weeklyReportChannel()).isEqualTo(ReportChannel.WHATSAPP);
            assertThat(saved.inventoryReportEnabled()).isFalse();
            assertThat(saved.inventoryReportChannel()).isEqualTo(ReportChannel.IN_APP_ONLY);
            assertThat(saved.stockAlertEnabled()).isTrue(); // from command
            assertThat(saved.stockAlertChannel()).isEqualTo(StockAlertChannel.BOTH);
        }
    }

    @Test
    void updatePreferences_publishesPreferenceUpdatedEvent() {
        // Given
        when(tenantPreferencesRepository.findByCurrentTenant()).thenReturn(Optional.of(existingPrefs));
        when(tenantPreferencesRepository.update(any())).thenAnswer(inv -> inv.getArgument(0));

        UpdateReportPreferencesCommand cmd = new UpdateReportPreferencesCommand(
                true, ReportChannel.WHATSAPP, "22:00:00",
                true, 0, "20:00:00", ReportChannel.WHATSAPP,
                true, ReportChannel.WHATSAPP,
                true, StockAlertChannel.PUSH,
                null
        );

        try (MockedStatic<TenantContext> ctx = mockStatic(TenantContext.class)) {
            ctx.when(TenantContext::getCurrentTenant).thenReturn(TENANT_ID);

            // When
            service.update(cmd);

            // Then
            ArgumentCaptor<PreferenceUpdatedEvent> eventCaptor = ArgumentCaptor.forClass(PreferenceUpdatedEvent.class);
            verify(eventPublisher).publishEvent(eventCaptor.capture());
            PreferenceUpdatedEvent event = eventCaptor.getValue();
            assertThat(event.tenantId()).isEqualTo(existingPrefs.id().toString());
            assertThat(event.newEodReportTime()).isEqualTo("22:00:00");
        }
    }

    @Test
    void updatePreferences_noPreferencesFound_throwsDomainException() {
        // Given
        when(tenantPreferencesRepository.findByCurrentTenant()).thenReturn(Optional.empty());

        UpdateReportPreferencesCommand cmd = new UpdateReportPreferencesCommand(
                true, ReportChannel.WHATSAPP, "20:00:00",
                true, 0, "20:00:00", ReportChannel.WHATSAPP,
                true, ReportChannel.WHATSAPP,
                true, StockAlertChannel.PUSH,
                null
        );

        // Then
        assertThatThrownBy(() -> service.update(cmd))
                .isInstanceOf(DomainException.class)
                .satisfies(e -> assertThat(((DomainException) e).getErrorCode())
                        .isEqualTo(ErrorCode.PREFERENCES_NOT_FOUND));
    }
}
