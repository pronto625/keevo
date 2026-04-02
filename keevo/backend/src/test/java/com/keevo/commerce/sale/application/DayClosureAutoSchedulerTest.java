package com.keevo.commerce.sale.application;

import com.keevo.commerce.sale.domain.model.*;
import com.keevo.commerce.sale.domain.port.in.CloseDayUseCase;
import com.keevo.commerce.sale.domain.port.in.CloseDayUseCase.CloseDayCommand;
import com.keevo.commerce.sale.domain.port.out.DayClosureRepository;
import com.keevo.commerce.sale.application.service.DayClosureAutoScheduler;
import com.keevo.identity.auth.domain.model.PlanType;
import com.keevo.identity.auth.domain.model.Tenant;
import com.keevo.identity.auth.domain.model.TenantStatus;
import com.keevo.identity.auth.domain.port.out.TenantRepository;
import com.keevo.identity.onboarding.domain.model.ReportChannel;
import com.keevo.identity.onboarding.domain.model.SectorType;
import com.keevo.identity.onboarding.domain.model.StockAlertChannel;
import com.keevo.identity.onboarding.domain.model.TenantPreferences;
import com.keevo.identity.onboarding.domain.port.out.TenantPreferencesRepository;
import com.keevo.store.store.domain.model.Store;
import com.keevo.store.store.domain.model.StoreType;
import com.keevo.store.store.domain.port.out.StoreRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * TDD RED tests for DayClosureAutoScheduler.
 * Story 4.4 — Clôture Journalière & Historique des Ventes
 * Story 7.5 — Per-tenant EOD time configuration
 *
 * Runs hourly; per-tenant eod_report_time read from tenant_preferences.
 */
@ExtendWith(MockitoExtension.class)
class DayClosureAutoSchedulerTest {

    @Mock
    private TenantRepository tenantRepository;

    @Mock
    private StoreRepository storeRepository;

    @Mock
    private DayClosureRepository dayClosureRepository;

    @Mock
    private CloseDayUseCase closeDayUseCase;

    @Mock
    private TenantPreferencesRepository tenantPreferencesRepository;

    private DayClosureAutoScheduler scheduler;

    private static final UUID STORE_ID = UUID.randomUUID();
    private static final String TENANT_SCHEMA = "kv_test";
    private static final UUID SYSTEM_UUID = UUID.fromString("00000000-0000-0000-0000-000000000000");
    private static final ZoneId WAT = ZoneId.of("Africa/Lagos");

    @BeforeEach
    void setUp() {
        // Default clock: 20:05 WAT on a fixed date
        Clock defaultClock = fixedClockAt("20:05", WAT);
        scheduler = new DayClosureAutoScheduler(
                tenantRepository, storeRepository, dayClosureRepository, closeDayUseCase,
                tenantPreferencesRepository, defaultClock);
    }

    @Test
    void scheduler_whenNoClosureForDay_triggersAutoClose() {
        // Given - active tenant with one store, no closure today, and prefs with default 20:00
        var tenant = createTenant(TENANT_SCHEMA, TenantStatus.ACTIVE);
        var store = createStore(STORE_ID, "Boutique Test");

        when(tenantRepository.findAll()).thenReturn(List.of(tenant));
        when(storeRepository.findAllActive()).thenReturn(List.of(store));
        when(dayClosureRepository.existsByStoreIdAndDate(eq(STORE_ID), any(LocalDate.class)))
                .thenReturn(false);
        when(closeDayUseCase.closeDay(any(CloseDayCommand.class)))
                .thenReturn(new DayClosureSummary(0, 0, null, null, 0, 0, 0, 0, 0));
        when(tenantPreferencesRepository.findByCurrentTenant())
                .thenReturn(Optional.of(prefsWithEodTime("20:00:00")));

        // When
        scheduler.runAutoClosure();

        // Then
        verify(closeDayUseCase).closeDay(any(CloseDayCommand.class));
    }

    @Test
    void scheduler_whenClosureExists_skipsTrigger() {
        // Given - store already closed today
        var tenant = createTenant(TENANT_SCHEMA, TenantStatus.ACTIVE);
        var store = createStore(STORE_ID, "Boutique Test");

        when(tenantRepository.findAll()).thenReturn(List.of(tenant));
        when(storeRepository.findAllActive()).thenReturn(List.of(store));
        when(dayClosureRepository.existsByStoreIdAndDate(eq(STORE_ID), any(LocalDate.class)))
                .thenReturn(true); // already closed
        when(tenantPreferencesRepository.findByCurrentTenant())
                .thenReturn(Optional.of(prefsWithEodTime("20:00:00")));

        // When
        scheduler.runAutoClosure();

        // Then - no closure triggered
        verify(closeDayUseCase, never()).closeDay(any());
    }

    @Test
    void scheduler_autoClose_setsIsAutomaticTrue() {
        // Given
        var tenant = createTenant(TENANT_SCHEMA, TenantStatus.ACTIVE);
        var store = createStore(STORE_ID, "Boutique Test");

        when(tenantRepository.findAll()).thenReturn(List.of(tenant));
        when(storeRepository.findAllActive()).thenReturn(List.of(store));
        when(dayClosureRepository.existsByStoreIdAndDate(eq(STORE_ID), any(LocalDate.class)))
                .thenReturn(false);
        when(closeDayUseCase.closeDay(any()))
                .thenReturn(new DayClosureSummary(0, 0, null, null, 0, 0, 0, 0, 0));
        when(tenantPreferencesRepository.findByCurrentTenant())
                .thenReturn(Optional.of(prefsWithEodTime("20:00:00")));

        // When
        scheduler.runAutoClosure();

        // Then - command has isAutomatic = true
        var commandCaptor = ArgumentCaptor.forClass(CloseDayCommand.class);
        verify(closeDayUseCase).closeDay(commandCaptor.capture());

        CloseDayCommand command = commandCaptor.getValue();
        assertThat(command.isAutomatic()).isTrue();
        assertThat(command.storeId()).isEqualTo(STORE_ID);
    }

    @Test
    void scheduler_skipsInactiveTenants() {
        // Given - suspended tenant
        var tenant = createTenant(TENANT_SCHEMA, TenantStatus.SUSPENDED);

        when(tenantRepository.findAll()).thenReturn(List.of(tenant));

        // When
        scheduler.runAutoClosure();

        // Then - no stores queried, no closure triggered
        verify(storeRepository, never()).findAllActive();
        verify(closeDayUseCase, never()).closeDay(any());
    }

    @Test
    void scheduler_processesMultipleStores() {
        // Given - tenant with 2 stores, only one needs closure
        var tenant = createTenant(TENANT_SCHEMA, TenantStatus.ACTIVE);
        UUID store1Id = UUID.randomUUID();
        UUID store2Id = UUID.randomUUID();
        var store1 = createStore(store1Id, "Store 1");
        var store2 = createStore(store2Id, "Store 2");

        when(tenantRepository.findAll()).thenReturn(List.of(tenant));
        when(storeRepository.findAllActive()).thenReturn(List.of(store1, store2));
        when(dayClosureRepository.existsByStoreIdAndDate(eq(store1Id), any(LocalDate.class)))
                .thenReturn(true);  // already closed
        when(dayClosureRepository.existsByStoreIdAndDate(eq(store2Id), any(LocalDate.class)))
                .thenReturn(false); // needs closure
        when(closeDayUseCase.closeDay(any()))
                .thenReturn(new DayClosureSummary(0, 0, null, null, 0, 0, 0, 0, 0));
        when(tenantPreferencesRepository.findByCurrentTenant())
                .thenReturn(Optional.of(prefsWithEodTime("20:00:00")));

        // When
        scheduler.runAutoClosure();

        // Then - only store2 gets closure
        var commandCaptor = ArgumentCaptor.forClass(CloseDayCommand.class);
        verify(closeDayUseCase, times(1)).closeDay(commandCaptor.capture());
        assertThat(commandCaptor.getValue().storeId()).isEqualTo(store2Id);
    }

    // ── Story 7.5: Per-tenant EOD time tests ──────────────────────────────────

    @Test
    void scheduler_tenant_with22h_NOT_triggered_at_20h() {
        // Given — current WAT time is 20:30, tenant configured for 22:00
        Clock clock2030 = fixedClockAt("20:30", WAT);
        var schedulerWith2030 = new DayClosureAutoScheduler(
                tenantRepository, storeRepository, dayClosureRepository, closeDayUseCase,
                tenantPreferencesRepository, clock2030);

        var tenant = createTenant(TENANT_SCHEMA, TenantStatus.ACTIVE);
        when(tenantRepository.findAll()).thenReturn(List.of(tenant));
        when(tenantPreferencesRepository.findByCurrentTenant())
                .thenReturn(Optional.of(prefsWithEodTime("22:00:00")));

        // When
        schedulerWith2030.runAutoClosure();

        // Then — NOT triggered because 20:30 < 22:00
        verify(closeDayUseCase, never()).closeDay(any());
    }

    @Test
    void scheduler_tenant_with20h_triggered_at_2005() {
        // Given — current WAT time is 20:05, tenant configured for 20:00
        // setUp already creates scheduler with 20:05 clock
        var tenant = createTenant(TENANT_SCHEMA, TenantStatus.ACTIVE);
        var store = createStore(STORE_ID, "Boutique Test");

        when(tenantRepository.findAll()).thenReturn(List.of(tenant));
        when(storeRepository.findAllActive()).thenReturn(List.of(store));
        when(dayClosureRepository.existsByStoreIdAndDate(eq(STORE_ID), any(LocalDate.class)))
                .thenReturn(false);
        when(closeDayUseCase.closeDay(any()))
                .thenReturn(new DayClosureSummary(0, 0, null, null, 0, 0, 0, 0, 0));
        when(tenantPreferencesRepository.findByCurrentTenant())
                .thenReturn(Optional.of(prefsWithEodTime("20:00:00")));

        // When
        scheduler.runAutoClosure();

        // Then — triggered because 20:05 >= 20:00
        verify(closeDayUseCase).closeDay(any());
    }

    @Test
    void scheduler_noPreferences_defaultsTo20hWAT() {
        // Given — no preferences found → fallback to 20:00 WAT. Clock is 20:05 WAT → should trigger
        var tenant = createTenant(TENANT_SCHEMA, TenantStatus.ACTIVE);
        var store = createStore(STORE_ID, "Boutique Test");

        when(tenantRepository.findAll()).thenReturn(List.of(tenant));
        when(storeRepository.findAllActive()).thenReturn(List.of(store));
        when(dayClosureRepository.existsByStoreIdAndDate(eq(STORE_ID), any(LocalDate.class)))
                .thenReturn(false);
        when(closeDayUseCase.closeDay(any()))
                .thenReturn(new DayClosureSummary(0, 0, null, null, 0, 0, 0, 0, 0));
        when(tenantPreferencesRepository.findByCurrentTenant())
                .thenReturn(Optional.empty()); // no preferences

        // When
        scheduler.runAutoClosure();

        // Then — fallback 20:00 WAT; clock is 20:05 WAT → triggered
        verify(closeDayUseCase).closeDay(any());
    }

    // ── Helper methods ────────────────────────────────────────────────────────

    private Tenant createTenant(String schemaName, TenantStatus status) {
        return new Tenant(UUID.randomUUID(), "KV-TEST", schemaName, "Test Tenant",
                status, PlanType.PREMIUM_TRIAL, Instant.now());
    }

    private Store createStore(UUID storeId, String name) {
        return new Store(storeId, name, StoreType.STORE, "Address", null, true, Instant.now(), Instant.now());
    }

    private TenantPreferences prefsWithEodTime(String eodTime) {
        return new TenantPreferences(
                UUID.randomUUID(), SectorType.OTHER, eodTime, true, Instant.now(),
                true, ReportChannel.WHATSAPP, true, 0, "20:00:00", ReportChannel.WHATSAPP,
                true, ReportChannel.WHATSAPP, StockAlertChannel.PUSH
        );
    }

    /**
     * Creates a fixed Clock that returns the given HH:mm WAT time.
     * We use 2026-01-01 as the base date (any fixed date works for time-only checks).
     */
    private Clock fixedClockAt(String hhMm, ZoneId zone) {
        String[] parts = hhMm.split(":");
        int hour = Integer.parseInt(parts[0]);
        int minute = Integer.parseInt(parts[1]);
        Instant fixedInstant = java.time.LocalDateTime.of(2026, 1, 1, 0, 0)
                .atZone(zone)
                .withHour(hour).withMinute(minute)
                .toInstant();
        return Clock.fixed(fixedInstant, zone);
    }
}
