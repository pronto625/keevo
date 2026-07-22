package com.keevo.commerce.sale.application.service;

import com.keevo.commerce.sale.domain.model.*;
import com.keevo.commerce.sale.domain.port.in.CloseDayUseCase;
import com.keevo.commerce.sale.domain.port.in.CloseDayUseCase.CloseDayCommand;
import com.keevo.commerce.sale.domain.port.out.DayClosureRepository;
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

import java.time.*;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Tests for DayClosureAutoScheduler.
 * Story 13.3 — Per-tenant eodReportTime + fallback 20h WAT (Option A).
 */
@ExtendWith(MockitoExtension.class)
class DayClosureAutoSchedulerTest {

    @Mock private TenantRepository tenantRepository;
    @Mock private StoreRepository storeRepository;
    @Mock private DayClosureRepository dayClosureRepository;
    @Mock private CloseDayUseCase closeDayUseCase;
    @Mock private TenantPreferencesRepository tenantPreferencesRepository;

    private DayClosureAutoScheduler scheduler;

    private static final ZoneId WAT = ZoneId.of("Africa/Lagos");
    private static final UUID STORE_ID = UUID.randomUUID();
    private static final String TENANT_SCHEMA = "kv_test";
    private static final UUID SYSTEM_UUID = UUID.fromString("00000000-0000-0000-0000-000000000000");
    // 2026-01-05 = Monday
    private static final LocalDate MONDAY_DATE = LocalDate.of(2026, 1, 5);

    @BeforeEach
    void setUp() {
        // Default: Monday 20:05 WAT
        Clock defaultClock = fixedClockAt(MONDAY_DATE, "20:05:00", WAT);
        scheduler = new DayClosureAutoScheduler(
                tenantRepository, storeRepository, dayClosureRepository,
                closeDayUseCase, tenantPreferencesRepository, defaultClock);
    }

    // ── Existing tests adapted for Clock + TenantPreferencesRepository ──────

    @Test
    void scheduler_whenNoClosureForDay_triggersAutoClose() {
        var tenant = createTenant(TENANT_SCHEMA, TenantStatus.ACTIVE);
        var store = createStore(STORE_ID, "Boutique Test");

        when(tenantRepository.findAll()).thenReturn(List.of(tenant));
        when(storeRepository.findAllActive()).thenReturn(List.of(store));
        when(tenantPreferencesRepository.findByCurrentTenant())
                .thenReturn(Optional.of(defaultEodPrefs("20:00:00", true)));
        when(dayClosureRepository.existsByStoreIdAndDate(eq(STORE_ID), any(LocalDate.class)))
                .thenReturn(false);
        when(closeDayUseCase.closeDay(any(CloseDayCommand.class)))
                .thenReturn(new DayClosure(UUID.randomUUID(), STORE_ID, SYSTEM_UUID, Instant.now(),
                        new DayClosureSummary(0, 0, null, null, 0, 0, 0, 0, 0), true, TENANT_SCHEMA));

        scheduler.runAutoClosure();

        verify(closeDayUseCase).closeDay(any(CloseDayCommand.class));
    }

    @Test
    void scheduler_whenClosureExists_skipsTrigger() {
        var tenant = createTenant(TENANT_SCHEMA, TenantStatus.ACTIVE);
        var store = createStore(STORE_ID, "Boutique Test");

        when(tenantRepository.findAll()).thenReturn(List.of(tenant));
        when(storeRepository.findAllActive()).thenReturn(List.of(store));
        when(tenantPreferencesRepository.findByCurrentTenant())
                .thenReturn(Optional.of(defaultEodPrefs("20:00:00", true)));
        when(dayClosureRepository.existsByStoreIdAndDate(eq(STORE_ID), any(LocalDate.class)))
                .thenReturn(true);

        scheduler.runAutoClosure();

        verify(closeDayUseCase, never()).closeDay(any());
    }

    @Test
    void scheduler_autoClose_setsIsAutomaticTrue() {
        var tenant = createTenant(TENANT_SCHEMA, TenantStatus.ACTIVE);
        var store = createStore(STORE_ID, "Boutique Test");

        when(tenantRepository.findAll()).thenReturn(List.of(tenant));
        when(storeRepository.findAllActive()).thenReturn(List.of(store));
        when(tenantPreferencesRepository.findByCurrentTenant())
                .thenReturn(Optional.of(defaultEodPrefs("20:00:00", true)));
        when(dayClosureRepository.existsByStoreIdAndDate(eq(STORE_ID), any(LocalDate.class)))
                .thenReturn(false);
        when(closeDayUseCase.closeDay(any()))
                .thenReturn(new DayClosure(UUID.randomUUID(), STORE_ID, SYSTEM_UUID, Instant.now(),
                        new DayClosureSummary(0, 0, null, null, 0, 0, 0, 0, 0), true, TENANT_SCHEMA));

        scheduler.runAutoClosure();

        var commandCaptor = ArgumentCaptor.forClass(CloseDayCommand.class);
        verify(closeDayUseCase).closeDay(commandCaptor.capture());
        CloseDayCommand command = commandCaptor.getValue();
        assertThat(command.isAutomatic()).isTrue();
        assertThat(command.storeId()).isEqualTo(STORE_ID);
    }

    @Test
    void scheduler_skipsInactiveTenants() {
        var tenant = createTenant(TENANT_SCHEMA, TenantStatus.SUSPENDED);
        when(tenantRepository.findAll()).thenReturn(List.of(tenant));

        scheduler.runAutoClosure();

        verify(storeRepository, never()).findAllActive();
        verify(closeDayUseCase, never()).closeDay(any());
    }

    @Test
    void scheduler_processesMultipleStores() {
        var tenant = createTenant(TENANT_SCHEMA, TenantStatus.ACTIVE);
        UUID store1Id = UUID.randomUUID();
        UUID store2Id = UUID.randomUUID();
        var store1 = createStore(store1Id, "Store 1");
        var store2 = createStore(store2Id, "Store 2");

        when(tenantRepository.findAll()).thenReturn(List.of(tenant));
        when(storeRepository.findAllActive()).thenReturn(List.of(store1, store2));
        when(tenantPreferencesRepository.findByCurrentTenant())
                .thenReturn(Optional.of(defaultEodPrefs("20:00:00", true)));
        when(dayClosureRepository.existsByStoreIdAndDate(eq(store1Id), any(LocalDate.class)))
                .thenReturn(true);
        when(dayClosureRepository.existsByStoreIdAndDate(eq(store2Id), any(LocalDate.class)))
                .thenReturn(false);
        when(closeDayUseCase.closeDay(any()))
                .thenReturn(new DayClosure(UUID.randomUUID(), store2Id, SYSTEM_UUID, Instant.now(),
                        new DayClosureSummary(0, 0, null, null, 0, 0, 0, 0, 0), true, TENANT_SCHEMA));

        scheduler.runAutoClosure();

        var commandCaptor = ArgumentCaptor.forClass(CloseDayCommand.class);
        verify(closeDayUseCase, times(1)).closeDay(commandCaptor.capture());
        assertThat(commandCaptor.getValue().storeId()).isEqualTo(store2Id);
    }

    // ── New tests for AC4 (per-tenant eodReportTime + fallback 20h WAT) ─────

    @Test
    void shouldFireEodAtConfiguredTenantTime() {
        // Given: Monday 20:05 WAT + prefs eodReportTime="20:00:00" + enabled
        var tenant = createTenant(TENANT_SCHEMA, TenantStatus.ACTIVE);
        var store = createStore(STORE_ID, "Boutique Test");

        when(tenantRepository.findAll()).thenReturn(List.of(tenant));
        when(storeRepository.findAllActive()).thenReturn(List.of(store));
        when(tenantPreferencesRepository.findByCurrentTenant())
                .thenReturn(Optional.of(defaultEodPrefs("20:00:00", true)));
        when(dayClosureRepository.existsByStoreIdAndDate(eq(STORE_ID), eq(MONDAY_DATE)))
                .thenReturn(false);
        when(closeDayUseCase.closeDay(any()))
                .thenReturn(new DayClosure(UUID.randomUUID(), STORE_ID, SYSTEM_UUID, Instant.now(),
                        new DayClosureSummary(0, 0, null, null, 0, 0, 0, 0, 0), true, TENANT_SCHEMA));

        scheduler.runAutoClosure();

        verify(closeDayUseCase).closeDay(any());
    }

    @Test
    void shouldNotFireEodBeforeConfiguredTime() {
        // Given: Monday 19:30 WAT + prefs eodReportTime="20:00:00"
        Clock earlyClock = fixedClockAt(MONDAY_DATE, "19:30:00", WAT);
        scheduler = new DayClosureAutoScheduler(tenantRepository, storeRepository,
                dayClosureRepository, closeDayUseCase, tenantPreferencesRepository, earlyClock);

        var tenant = createTenant(TENANT_SCHEMA, TenantStatus.ACTIVE);
        when(tenantRepository.findAll()).thenReturn(List.of(tenant));
        when(tenantPreferencesRepository.findByCurrentTenant())
                .thenReturn(Optional.of(defaultEodPrefs("20:00:00", true)));

        scheduler.runAutoClosure();

        verify(storeRepository, never()).findAllActive();
        verifyNoInteractions(closeDayUseCase);
    }

    @Test
    void shouldSkipEodWhenDisabled() {
        // Given: Monday 20:05 WAT + prefs eodReportEnabled=false
        var tenant = createTenant(TENANT_SCHEMA, TenantStatus.ACTIVE);
        when(tenantRepository.findAll()).thenReturn(List.of(tenant));
        when(tenantPreferencesRepository.findByCurrentTenant())
                .thenReturn(Optional.of(defaultEodPrefs("20:00:00", false)));

        scheduler.runAutoClosure();

        verify(storeRepository, never()).findAllActive();
        verifyNoInteractions(closeDayUseCase);
    }

    @Test
    void shouldUseDefaultEodTimeWhenPrefsNull() {
        // Given: no prefs → fallback 20:00 WAT, trigger at 20:05
        var tenant = createTenant(TENANT_SCHEMA, TenantStatus.ACTIVE);
        var store = createStore(STORE_ID, "Boutique Test");

        when(tenantRepository.findAll()).thenReturn(List.of(tenant));
        when(storeRepository.findAllActive()).thenReturn(List.of(store));
        when(tenantPreferencesRepository.findByCurrentTenant())
                .thenReturn(Optional.empty());  // no prefs
        when(dayClosureRepository.existsByStoreIdAndDate(eq(STORE_ID), eq(MONDAY_DATE)))
                .thenReturn(false);
        when(closeDayUseCase.closeDay(any()))
                .thenReturn(new DayClosure(UUID.randomUUID(), STORE_ID, SYSTEM_UUID, Instant.now(),
                        new DayClosureSummary(0, 0, null, null, 0, 0, 0, 0, 0), true, TENANT_SCHEMA));

        scheduler.runAutoClosure();

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

    private TenantPreferences defaultEodPrefs(String eodReportTime, boolean eodReportEnabled) {
        return new TenantPreferences(
                UUID.randomUUID(), SectorType.OTHER, eodReportTime, true, Instant.now(),
                eodReportEnabled, ReportChannel.WHATSAPP,
                true, 0, "20:00:00", ReportChannel.WHATSAPP,
                true, ReportChannel.WHATSAPP, StockAlertChannel.PUSH,
                true
        );
    }

    private Clock fixedClockAt(LocalDate date, String hhMmSs, ZoneId zone) {
        String[] parts = hhMmSs.split(":");
        Instant fixedInstant = LocalDateTime.of(
                date.getYear(), date.getMonth(), date.getDayOfMonth(),
                Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]))
                .atZone(zone)
                .toInstant();
        return Clock.fixed(fixedInstant, zone);
    }
}
