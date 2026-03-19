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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * TDD RED tests for DayClosureAutoScheduler.
 * Story 4.4 — Clôture Journalière & Historique des Ventes
 *
 * Runs at 20h00 WAT (19:00 UTC) to auto-close day for stores without manual closure.
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

    private DayClosureAutoScheduler scheduler;

    private static final UUID STORE_ID = UUID.randomUUID();
    private static final String TENANT_SCHEMA = "kv_test";
    private static final UUID SYSTEM_UUID = UUID.fromString("00000000-0000-0000-0000-000000000000");

    @BeforeEach
    void setUp() {
        scheduler = new DayClosureAutoScheduler(
                tenantRepository, storeRepository, dayClosureRepository, closeDayUseCase);
    }

    @Test
    void scheduler_whenNoClosureForDay_triggersAutoClose() {
        // Given - active tenant with one store, no closure today
        var tenant = createTenant(TENANT_SCHEMA, TenantStatus.ACTIVE);
        var store = createStore(STORE_ID, "Boutique Test");

        when(tenantRepository.findAll()).thenReturn(List.of(tenant));
        when(storeRepository.findAllActive()).thenReturn(List.of(store));
        when(dayClosureRepository.existsByStoreIdAndDate(eq(STORE_ID), any(LocalDate.class)))
                .thenReturn(false);
        when(closeDayUseCase.closeDay(any(CloseDayCommand.class)))
                .thenReturn(new DayClosureSummary(0, 0, null, null, 0, 0, 0, 0, 0));

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

        // When
        scheduler.runAutoClosure();

        // Then - only store2 gets closure
        var commandCaptor = ArgumentCaptor.forClass(CloseDayCommand.class);
        verify(closeDayUseCase, times(1)).closeDay(commandCaptor.capture());
        assertThat(commandCaptor.getValue().storeId()).isEqualTo(store2Id);
    }

    // ── Helper methods ────────────────────────────────────────────────────────

    private Tenant createTenant(String schemaName, TenantStatus status) {
        return new Tenant(UUID.randomUUID(), "KV-TEST", schemaName, "Test Tenant",
                status, PlanType.PREMIUM_TRIAL, Instant.now());
    }

    private Store createStore(UUID storeId, String name) {
        return new Store(storeId, name, StoreType.STORE, "Address", null, true, Instant.now(), Instant.now());
    }
}
