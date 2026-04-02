package com.keevo.reporting.report.application.service;

import com.keevo.identity.auth.domain.model.PlanType;
import com.keevo.identity.auth.domain.model.Tenant;
import com.keevo.identity.auth.domain.model.TenantStatus;
import com.keevo.identity.auth.domain.port.out.TenantRepository;
import com.keevo.identity.onboarding.domain.model.ReportChannel;
import com.keevo.identity.onboarding.domain.model.SectorType;
import com.keevo.identity.onboarding.domain.model.StockAlertChannel;
import com.keevo.identity.onboarding.domain.model.TenantPreferences;
import com.keevo.identity.onboarding.domain.port.out.TenantPreferencesRepository;
import com.keevo.reporting.report.domain.model.EndOfDayReport;
import com.keevo.reporting.report.domain.model.ReportType;
import com.keevo.reporting.report.domain.port.in.GenerateWeeklyReportUseCase;
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

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * WeeklyReportSchedulerTest — TDD RED tests for the hourly weekly report scheduler.
 * Story 7.3 — Task 4.2.
 *
 * Mirrors DayClosureAutoSchedulerTest — Clock injection, per-tenant prefs, guard check.
 *
 * weeklyReportDay encoding: 0=Sunday, 1=Monday … 6=Saturday.
 * Default: Sunday (0) at 20:00:00.
 */
@ExtendWith(MockitoExtension.class)
class WeeklyReportSchedulerTest {

    @Mock private TenantRepository tenantRepository;
    @Mock private StoreRepository storeRepository;
    @Mock private EndOfDayReportRepository reportRepository;
    @Mock private GenerateWeeklyReportUseCase weeklyReportGenerator;
    @Mock private TenantPreferencesRepository tenantPreferencesRepository;

    private WeeklyReportScheduler scheduler;

    private static final ZoneId WAT = ZoneId.of("Africa/Lagos");
    private static final String TENANT_SCHEMA = "kv_test01";

    // Fixed reference Sunday: 2026-01-04 (known to be a Sunday)
    private static final LocalDate SUNDAY_DATE = LocalDate.of(2026, 1, 4);
    private static final LocalDate SATURDAY_DATE = LocalDate.of(2026, 1, 3);

    @BeforeEach
    void setUp() {
        // Default: Sunday 20:05 WAT
        Clock defaultClock = fixedClockAt(SUNDAY_DATE, "20:05:00", WAT);
        scheduler = new WeeklyReportScheduler(
                tenantRepository, storeRepository, reportRepository,
                weeklyReportGenerator, tenantPreferencesRepository, defaultClock);
    }

    @Test
    void correctDayAndTime_triggersReport() {
        // Given: Sunday 20:05 + prefs say Sunday 20:00 + not yet generated
        var tenant = activeTenant();
        var store = activeStore();

        when(tenantRepository.findAll()).thenReturn(List.of(tenant));
        when(storeRepository.findAllActive()).thenReturn(List.of(store));
        when(tenantPreferencesRepository.findByCurrentTenant())
                .thenReturn(Optional.of(prefs(0, "20:00:00", true)));
        when(reportRepository.findByDateAndTenant(any(LocalDate.class), eq(TENANT_SCHEMA), eq(ReportType.WEEKLY)))
                .thenReturn(List.of());
        when(weeklyReportGenerator.generateWeeklyReport(any()))
                .thenReturn(mock(EndOfDayReport.class));

        scheduler.runWeeklyReport();

        verify(weeklyReportGenerator).generateWeeklyReport(any());
    }

    @Test
    void correctDay_wrongTime_doesNotTrigger() {
        // Given: Sunday 19:30 — too early, prefs say Sunday 20:00
        Clock earlyClock = fixedClockAt(SUNDAY_DATE, "19:30:00", WAT);
        scheduler = new WeeklyReportScheduler(tenantRepository, storeRepository, reportRepository,
                weeklyReportGenerator, tenantPreferencesRepository, earlyClock);

        var tenant = activeTenant();
        when(tenantRepository.findAll()).thenReturn(List.of(tenant));
        when(tenantPreferencesRepository.findByCurrentTenant())
                .thenReturn(Optional.of(prefs(0, "20:00:00", true)));

        scheduler.runWeeklyReport();

        verifyNoInteractions(weeklyReportGenerator);
    }

    @Test
    void wrongDay_correctTime_doesNotTrigger() {
        // Given: Saturday 20:05 but prefs say Sunday (day=0) 20:00
        Clock saturdayClock = fixedClockAt(SATURDAY_DATE, "20:05:00", WAT);
        scheduler = new WeeklyReportScheduler(tenantRepository, storeRepository, reportRepository,
                weeklyReportGenerator, tenantPreferencesRepository, saturdayClock);

        var tenant = activeTenant();
        when(tenantRepository.findAll()).thenReturn(List.of(tenant));
        when(tenantPreferencesRepository.findByCurrentTenant())
                .thenReturn(Optional.of(prefs(0, "20:00:00", true)));

        scheduler.runWeeklyReport();

        verifyNoInteractions(weeklyReportGenerator);
    }

    @Test
    void alreadyGeneratedThisWeek_skipsTrigger() {
        // Given: correct day and time, but WEEKLY report already exists
        var tenant = activeTenant();

        when(tenantRepository.findAll()).thenReturn(List.of(tenant));
        when(tenantPreferencesRepository.findByCurrentTenant())
                .thenReturn(Optional.of(prefs(0, "20:00:00", true)));
        when(reportRepository.findByDateAndTenant(any(LocalDate.class), eq(TENANT_SCHEMA), eq(ReportType.WEEKLY)))
                .thenReturn(List.of(mock(EndOfDayReport.class)));  // already exists

        scheduler.runWeeklyReport();

        verifyNoInteractions(weeklyReportGenerator);
    }

    @Test
    void weeklyReportDisabled_skipsGracefully() {
        var tenant = activeTenant();
        when(tenantRepository.findAll()).thenReturn(List.of(tenant));
        when(tenantPreferencesRepository.findByCurrentTenant())
                .thenReturn(Optional.of(prefs(0, "20:00:00", false)));  // disabled

        scheduler.runWeeklyReport();

        verifyNoInteractions(weeklyReportGenerator);
        verifyNoInteractions(storeRepository);
    }

    @Test
    void inactiveTenant_skipsGracefully() {
        var suspended = new Tenant(UUID.randomUUID(), "KV-TEST01", TENANT_SCHEMA, "Test",
                TenantStatus.SUSPENDED, PlanType.PREMIUM_TRIAL, Instant.now());
        when(tenantRepository.findAll()).thenReturn(List.of(suspended));

        scheduler.runWeeklyReport();

        verifyNoInteractions(weeklyReportGenerator);
        verifyNoInteractions(tenantPreferencesRepository);
    }

    @Test
    void configuredSaturday_triggersOnSaturday() {
        // Given: Saturday clock + prefs day=6 (Saturday) 20:00
        Clock saturdayClock = fixedClockAt(SATURDAY_DATE, "20:05:00", WAT);
        scheduler = new WeeklyReportScheduler(tenantRepository, storeRepository, reportRepository,
                weeklyReportGenerator, tenantPreferencesRepository, saturdayClock);

        var tenant = activeTenant();
        var store = activeStore();
        when(tenantRepository.findAll()).thenReturn(List.of(tenant));
        when(storeRepository.findAllActive()).thenReturn(List.of(store));
        when(tenantPreferencesRepository.findByCurrentTenant())
                .thenReturn(Optional.of(prefs(6, "20:00:00", true)));  // Saturday
        when(reportRepository.findByDateAndTenant(any(LocalDate.class), eq(TENANT_SCHEMA), eq(ReportType.WEEKLY)))
                .thenReturn(List.of());
        when(weeklyReportGenerator.generateWeeklyReport(any()))
                .thenReturn(mock(EndOfDayReport.class));

        scheduler.runWeeklyReport();

        verify(weeklyReportGenerator).generateWeeklyReport(any());
    }

    @Test
    void commandHasIsAutomaticTrue() {
        var tenant = activeTenant();
        var store = activeStore();
        when(tenantRepository.findAll()).thenReturn(List.of(tenant));
        when(storeRepository.findAllActive()).thenReturn(List.of(store));
        when(tenantPreferencesRepository.findByCurrentTenant())
                .thenReturn(Optional.of(prefs(0, "20:00:00", true)));
        when(reportRepository.findByDateAndTenant(any(LocalDate.class), eq(TENANT_SCHEMA), eq(ReportType.WEEKLY)))
                .thenReturn(List.of());
        when(weeklyReportGenerator.generateWeeklyReport(any()))
                .thenReturn(mock(EndOfDayReport.class));

        scheduler.runWeeklyReport();

        var captor = ArgumentCaptor.forClass(GenerateWeeklyReportUseCase.WeeklyReportCommand.class);
        verify(weeklyReportGenerator).generateWeeklyReport(captor.capture());
        assertThat(captor.getValue().isAutomatic()).isTrue();
    }

    @Test
    void commandUsesWeeklyReportChannel() {
        var tenant = activeTenant();
        var store = activeStore();
        when(tenantRepository.findAll()).thenReturn(List.of(tenant));
        when(storeRepository.findAllActive()).thenReturn(List.of(store));
        when(tenantPreferencesRepository.findByCurrentTenant())
                .thenReturn(Optional.of(prefsWithChannel(0, "20:00:00", ReportChannel.IN_APP_ONLY)));
        when(reportRepository.findByDateAndTenant(any(LocalDate.class), eq(TENANT_SCHEMA), eq(ReportType.WEEKLY)))
                .thenReturn(List.of());
        when(weeklyReportGenerator.generateWeeklyReport(any()))
                .thenReturn(mock(EndOfDayReport.class));

        scheduler.runWeeklyReport();

        var captor = ArgumentCaptor.forClass(GenerateWeeklyReportUseCase.WeeklyReportCommand.class);
        verify(weeklyReportGenerator).generateWeeklyReport(captor.capture());
        assertThat(captor.getValue().deliveryChannel()).isEqualTo(ReportChannel.IN_APP_ONLY);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Tenant activeTenant() {
        return new Tenant(UUID.randomUUID(), "KV-TEST01", TENANT_SCHEMA, "Test Tenant",
                TenantStatus.ACTIVE, PlanType.PREMIUM_TRIAL, Instant.now());
    }

    private Store activeStore() {
        return new Store(UUID.randomUUID(), "Boutique Test", StoreType.STORE,
                "Yaoundé", null, true, Instant.now(), Instant.now());
    }

    private TenantPreferences prefs(int weeklyDay, String weeklyTime, boolean enabled) {
        return new TenantPreferences(
                UUID.randomUUID(), SectorType.OTHER, "20:00:00", true, Instant.now(),
                true, ReportChannel.WHATSAPP,
                enabled, weeklyDay, weeklyTime, ReportChannel.WHATSAPP,
                true, ReportChannel.WHATSAPP, StockAlertChannel.PUSH
        );
    }

    private TenantPreferences prefsWithChannel(int weeklyDay, String weeklyTime, ReportChannel channel) {
        return new TenantPreferences(
                UUID.randomUUID(), SectorType.OTHER, "20:00:00", true, Instant.now(),
                true, ReportChannel.WHATSAPP,
                true, weeklyDay, weeklyTime, channel,
                true, ReportChannel.WHATSAPP, StockAlertChannel.PUSH
        );
    }

    /**
     * Creates a fixed Clock for a given date + HH:mm:ss time in WAT.
     * Clock.instant() returns UTC, Clock.getZone() returns UTC also — 
     * the scheduler uses LocalDate.now(clock) and LocalTime.now(clock) where the zone is WAT.
     */
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
