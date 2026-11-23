package com.keevo.identity.onboarding.application.service;

import com.keevo.identity.onboarding.application.factory.SectorTemplateFactory;
import com.keevo.identity.onboarding.application.strategy.ClothingTemplateStrategy;
import com.keevo.identity.onboarding.domain.model.SectorType;
import com.keevo.identity.onboarding.domain.model.TenantPreferences;
import com.keevo.identity.onboarding.domain.port.out.OnboardingStoreRepository;
import com.keevo.identity.onboarding.domain.port.out.TenantPreferencesRepository;
import com.keevo.shared.event.catalog.onboarding.OnboardingCompletedEvent;
import com.keevo.shared.event.catalog.onboarding.SectorKind;
import com.keevo.shared.event.catalog.onboarding.TenantOnboardedEvent;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("OnboardingService.onboardIfAbsent — Story 22.19 (D2)")
class SetupSectorOnboardingTest {

    @Mock TenantPreferencesRepository preferences;
    @Mock OnboardingStoreRepository stores;
    @Mock ApplicationEventPublisher events;
    @Mock SectorTemplateFactory templates;

    private OnboardingService service;
    private final UUID owner = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new OnboardingService(preferences, stores, events, templates);
        TenantContext.setCurrentTenant("kv_setup1");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("no preferences: sector categories seeded, row created with the sector, store never renamed")
    void firstCall() {
        when(preferences.findByCurrentTenant()).thenReturn(Optional.empty());
        when(templates.create(SectorType.CLOTHING)).thenReturn(new ClothingTemplateStrategy());

        assertThat(service.onboardIfAbsent(SectorType.CLOTHING, "Boutique Aicha", owner)).isTrue();

        ArgumentCaptor<TenantPreferences> saved = ArgumentCaptor.forClass(TenantPreferences.class);
        verify(preferences).save(saved.capture());
        assertThat(saved.getValue().sectorType()).isEqualTo(SectorType.CLOTHING);
        verify(events).publishEvent((Object) argThat(e -> e instanceof TenantOnboardedEvent t
                && t.sectorType() == SectorKind.CLOTHING && !t.templateCategories().isEmpty()));
        verify(events).publishEvent((Object) argThat(e -> e instanceof OnboardingCompletedEvent o
                && "Boutique Aicha".equals(o.storeName()) && o.actorId().equals(owner) && o.skipSaleFlowRules()));
        verifyNoInteractions(stores);
    }

    @Test
    @DisplayName("preferences with a sector: no-op (second call)")
    void secondCall() {
        when(preferences.findByCurrentTenant()).thenReturn(Optional.of(
                TenantPreferences.defaults(SectorType.FOOD_GROCERY, Instant.now())));

        assertThat(service.onboardIfAbsent(SectorType.FOOD_GROCERY, "X", owner)).isFalse();

        verify(preferences, never()).save(any());
        verify(preferences, never()).update(any());
        verifyNoInteractions(events, templates, stores);
    }

    @Test
    @DisplayName("a sector-less row (a settings write came first): completed with the sector, no second row")
    void sectorLessRow() {
        TenantPreferences row = TenantPreferences.defaults(null, Instant.now());
        when(preferences.findByCurrentTenant()).thenReturn(Optional.of(row));
        when(templates.create(SectorType.OTHER)).thenReturn(new ClothingTemplateStrategy());

        assertThat(service.onboardIfAbsent(null, "X", owner)).isTrue();

        ArgumentCaptor<TenantPreferences> updated = ArgumentCaptor.forClass(TenantPreferences.class);
        verify(preferences).update(updated.capture());
        assertThat(updated.getValue().id()).isEqualTo(row.id());
        assertThat(updated.getValue().sectorType()).isEqualTo(SectorType.OTHER);
        verify(preferences, never()).save(any());
    }

    @Test
    @DisplayName("/onboarding/complete after a settings write: the sector-less row is completed (no « already "
            + "completed », no second row), under the tenant's preferences lock")
    void completeReusesSectorLessRow() {
        TenantPreferences row = TenantPreferences.defaults(null, Instant.now());
        when(preferences.hasOnboardingCompleted()).thenReturn(false);
        when(preferences.findByCurrentTenant()).thenReturn(Optional.of(row));
        when(templates.create(SectorType.CLOTHING)).thenReturn(new ClothingTemplateStrategy());

        service.complete(new com.keevo.identity.onboarding.domain.port.in.CompleteOnboardingCommand(
                SectorType.CLOTHING, "Boutique", owner));

        var order = inOrder(preferences);
        order.verify(preferences).lockForCreation();
        order.verify(preferences).hasOnboardingCompleted();
        verify(preferences).update(argThat(p -> p.id().equals(row.id()) && p.sectorType() == SectorType.CLOTHING));
        verify(preferences, never()).save(any());
    }
}
