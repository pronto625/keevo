package com.keevo.reporting.report.application.service;

import com.keevo.identity.onboarding.adapter.in.api.IdentityOnboardingApiTestSupport;
import com.keevo.store.store.adapter.in.api.StoreApiTestSupport;
import com.keevo.identity.auth.adapter.in.api.IdentityAuthApiTestSupport;
import com.keevo.identity.api.PlanType;
import com.keevo.identity.auth.domain.model.Tenant;
import com.keevo.identity.api.TenantStatus;
import com.keevo.identity.auth.domain.port.out.TenantRepository;
import com.keevo.identity.api.ReportChannel;
import com.keevo.identity.onboarding.domain.model.SectorType;
import com.keevo.identity.api.StockAlertChannel;
import com.keevo.identity.onboarding.domain.model.TenantPreferences;
import com.keevo.identity.onboarding.domain.port.out.TenantPreferencesRepository;
import com.keevo.reporting.report.domain.model.EndOfDayReport;
import com.keevo.reporting.report.domain.model.ReportType;
import com.keevo.reporting.report.domain.port.in.GenerateWeeklyReportUseCase;
import com.keevo.reporting.report.domain.port.out.EndOfDayReportRepository;
import com.keevo.store.store.domain.model.Store;
import com.keevo.store.api.StoreType;
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
                IdentityAuthApiTestSupport.tenantQueryPort(tenantRepository), StoreApiTestSupport.queryPort(storeRepository), reportRepository,
                weeklyReportGenerator, IdentityOnboardingApiTestSupport.preferencesQueryPort(tenantPreferencesRepository), new WeeklyReportWindowResolver(reportRepository), defaultClock);
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
        scheduler = new WeeklyReportScheduler(IdentityAuthApiTestSupport.tenantQueryPort(tenantRepository), StoreApiTestSupport.queryPort(storeRepository), reportRepository,
                weeklyReportGenerator, IdentityOnboardingApiTestSupport.preferencesQueryPort(tenantPreferencesRepository), new WeeklyReportWindowResolver(reportRepository), earlyClock);

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
        scheduler = new WeeklyReportScheduler(IdentityAuthApiTestSupport.tenantQueryPort(tenantRepository), StoreApiTestSupport.queryPort(storeRepository), reportRepository,
                weeklyReportGenerator, IdentityOnboardingApiTestSupport.preferencesQueryPort(tenantPreferencesRepository), new WeeklyReportWindowResolver(reportRepository), saturdayClock);

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
        var store = activeStore();

        when(tenantRepository.findAll()).thenReturn(List.of(tenant));
        when(storeRepository.findAllActive()).thenReturn(List.of(store));
        when(tenantPreferencesRepository.findByCurrentTenant())
                .thenReturn(Optional.of(prefs(0, "20:00:00", true)));
        when(reportRepository.findByDateAndTenant(any(LocalDate.class), eq(TENANT_SCHEMA), eq(ReportType.WEEKLY)))
                .thenReturn(List.of(weeklyReportFor(store.id())));  // already exists

        scheduler.runWeeklyReport();

        verifyNoInteractions(weeklyReportGenerator);
    }

    // v1s-13-11: the guard is per store — a store whose generation failed on a previous tick
    // is retried even though the other stores already have their report.
    @Test
    void alreadyGeneratedForOneStoreOnly_generatesTheMissingStoreOnly() {
        var tenant = activeTenant();
        var done = activeStore();
        var missing = activeStore();

        when(tenantRepository.findAll()).thenReturn(List.of(tenant));
        when(storeRepository.findAllActive()).thenReturn(List.of(done, missing));
        when(tenantPreferencesRepository.findByCurrentTenant())
                .thenReturn(Optional.of(prefs(0, "20:00:00", true)));
        when(reportRepository.findByDateAndTenant(SUNDAY_DATE, TENANT_SCHEMA, ReportType.WEEKLY))
                .thenReturn(List.of(weeklyReportFor(done.id())));
        when(reportRepository.findByDateAndTenant(SUNDAY_DATE.minusDays(7), TENANT_SCHEMA, ReportType.WEEKLY))
                .thenReturn(List.of());
        when(weeklyReportGenerator.generateWeeklyReport(any()))
                .thenReturn(mock(EndOfDayReport.class));

        scheduler.runWeeklyReport();

        var captor = ArgumentCaptor.forClass(GenerateWeeklyReportUseCase.WeeklyReportCommand.class);
        verify(weeklyReportGenerator, times(1)).generateWeeklyReport(captor.capture());
        assertThat(captor.getValue().storeId()).isEqualTo(missing.id());
    }

    private EndOfDayReport weeklyReportFor(UUID storeId) {
        return EndOfDayReport.createNew(TENANT_SCHEMA, storeId, "Boutique Test", null, null,
                ReportType.WEEKLY, SUNDAY_DATE, "rapport", 0, 0, true);
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
        scheduler = new WeeklyReportScheduler(IdentityAuthApiTestSupport.tenantQueryPort(tenantRepository), StoreApiTestSupport.queryPort(storeRepository), reportRepository,
                weeklyReportGenerator, IdentityOnboardingApiTestSupport.preferencesQueryPort(tenantPreferencesRepository), new WeeklyReportWindowResolver(reportRepository), saturdayClock);

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

    // Triggered on a Saturday, the report covers the last completed week (Mon–Sun before),
    // not the current partial week.
    @Test
    void shouldReportLastCompletedWeekWhenTriggeredOnSaturday() {
        // Given: Saturday 20:05 WAT + prefs day=6 (Saturday)
        Clock saturdayClock = fixedClockAt(SATURDAY_DATE, "20:05:00", WAT);
        scheduler = new WeeklyReportScheduler(IdentityAuthApiTestSupport.tenantQueryPort(tenantRepository), StoreApiTestSupport.queryPort(storeRepository), reportRepository,
                weeklyReportGenerator, IdentityOnboardingApiTestSupport.preferencesQueryPort(tenantPreferencesRepository), new WeeklyReportWindowResolver(reportRepository), saturdayClock);

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

        var captor = ArgumentCaptor.forClass(GenerateWeeklyReportUseCase.WeeklyReportCommand.class);
        verify(weeklyReportGenerator).generateWeeklyReport(captor.capture());
        LocalDate expectedSunday = LocalDate.of(2025, 12, 28);
        LocalDate expectedMonday = LocalDate.of(2025, 12, 22);
        assertThat(captor.getValue().weekStart()).isEqualTo(expectedMonday.atStartOfDay(WAT).toInstant());
        // Generated after the week: it ends at Sunday's last microsecond, and takes every
        // sale received until now (v1s-13-11).
        assertThat(captor.getValue().weekEnd())
                .isEqualTo(expectedSunday.plusDays(1).atStartOfDay(WAT).toInstant().minusNanos(1000));
        // v1s-13-13: arrival limit = now − commit-safety margin.
        assertThat(captor.getValue().receivedUntil()).isEqualTo(saturdayClock.instant().minusSeconds(5));
        assertThat(captor.getValue().lateFrom()).isNull();
        // The "already generated" guard looks at the report's own date (its Sunday).
        verify(reportRepository).findByDateAndTenant(expectedSunday, TENANT_SCHEMA, ReportType.WEEKLY);
    }

    @Test
    void shouldReportTheWeekEndingTodayWhenTriggeredOnSunday() {
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
        assertThat(captor.getValue().weekStart())
                .isEqualTo(SUNDAY_DATE.minusDays(6).atStartOfDay(WAT).toInstant());
        // Generated on Sunday 20:05: the window ends at the generation time; the rest of
        // Sunday goes to next week's report (v1s-13-11 D2).
        // v1s-13-13: the anchor is now − commit-safety margin.
        Instant now = SUNDAY_DATE.atTime(20, 5).atZone(WAT).toInstant().minusSeconds(5);
        assertThat(captor.getValue().weekEnd()).isEqualTo(now);
        assertThat(captor.getValue().receivedUntil()).isEqualTo(now);
    }

    // v1s-13-11 (D2): chained on the store's previous weekly report.
    @Test
    void sundayTrigger_chainsOnPreviousWeeklyReportOfTheStore() {
        var tenant = activeTenant();
        var store = activeStore();
        Instant previousCreatedAt = SUNDAY_DATE.minusDays(7).atTime(20, 0).atZone(WAT).toInstant()
                .plusMillis(500);
        EndOfDayReport previous = new EndOfDayReport(UUID.randomUUID(), TENANT_SCHEMA, store.id(),
                "Boutique Test", null, null, ReportType.WEEKLY, SUNDAY_DATE.minusDays(7), "prev",
                com.keevo.reporting.report.domain.model.DeliveryStatus.SENT, 1, null, 0, 0, true,
                previousCreatedAt);
        when(tenantRepository.findAll()).thenReturn(List.of(tenant));
        when(storeRepository.findAllActive()).thenReturn(List.of(store));
        when(tenantPreferencesRepository.findByCurrentTenant())
                .thenReturn(Optional.of(prefs(0, "20:00:00", true)));
        when(reportRepository.findByDateAndTenant(SUNDAY_DATE, TENANT_SCHEMA, ReportType.WEEKLY))
                .thenReturn(List.of());
        when(reportRepository.findByDateAndTenant(SUNDAY_DATE.minusDays(7), TENANT_SCHEMA, ReportType.WEEKLY))
                .thenReturn(List.of(previous));
        when(weeklyReportGenerator.generateWeeklyReport(any()))
                .thenReturn(mock(EndOfDayReport.class));

        scheduler.runWeeklyReport();

        var captor = ArgumentCaptor.forClass(GenerateWeeklyReportUseCase.WeeklyReportCommand.class);
        verify(weeklyReportGenerator).generateWeeklyReport(captor.capture());
        assertThat(captor.getValue().weekStart()).isEqualTo(previousCreatedAt.plusNanos(1000));
        assertThat(captor.getValue().lateFrom()).isEqualTo(previousCreatedAt);
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
                true, ReportChannel.WHATSAPP, StockAlertChannel.PUSH,
                true
        );
    }

    private TenantPreferences prefsWithChannel(int weeklyDay, String weeklyTime, ReportChannel channel) {
        return new TenantPreferences(
                UUID.randomUUID(), SectorType.OTHER, "20:00:00", true, Instant.now(),
                true, ReportChannel.WHATSAPP,
                true, weeklyDay, weeklyTime, channel,
                true, ReportChannel.WHATSAPP, StockAlertChannel.PUSH,
                true
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

    @Test
    void story139_reportingDisabled_tenantSkipped_readOnlyStillGenerated() {
        com.keevo.shared.module.ModuleRegistry modules = installedRegistry();
        scheduler = new WeeklyReportScheduler(IdentityAuthApiTestSupport.tenantQueryPort(tenantRepository),
                StoreApiTestSupport.queryPort(storeRepository), reportRepository, weeklyReportGenerator,
                IdentityOnboardingApiTestSupport.preferencesQueryPort(tenantPreferencesRepository),
                new WeeklyReportWindowResolver(reportRepository), fixedClockAt(SUNDAY_DATE, "20:05:00", WAT), modules);
        var tenant = activeTenant();
        when(tenantRepository.findAll()).thenReturn(List.of(tenant));
        when(modules.moduleStates(TENANT_SCHEMA)).thenReturn(states(com.keevo.shared.module.ModuleState.DISABLED,
                com.keevo.shared.module.ModuleState.ENABLED));

        scheduler.runWeeklyReport();
        verifyNoInteractions(weeklyReportGenerator);
        verifyNoInteractions(tenantPreferencesRepository);

        when(modules.moduleStates(TENANT_SCHEMA)).thenReturn(states(com.keevo.shared.module.ModuleState.READ_ONLY,
                com.keevo.shared.module.ModuleState.READ_ONLY));
        when(storeRepository.findAllActive()).thenReturn(List.of(activeStore()));
        when(tenantPreferencesRepository.findByCurrentTenant())
                .thenReturn(Optional.of(prefs(0, "20:00:00", true)));
        when(reportRepository.findByDateAndTenant(any(LocalDate.class), eq(TENANT_SCHEMA), eq(ReportType.WEEKLY)))
                .thenReturn(List.of());
        when(weeklyReportGenerator.generateWeeklyReport(any())).thenReturn(mock(EndOfDayReport.class));
        scheduler.runWeeklyReport();
        verify(weeklyReportGenerator).generateWeeklyReport(any());
    }

    @Test
    void story1310a_advancedReportsDisabled_tenantSkipped() {
        com.keevo.shared.module.ModuleRegistry modules = installedRegistry();
        scheduler = new WeeklyReportScheduler(IdentityAuthApiTestSupport.tenantQueryPort(tenantRepository),
                StoreApiTestSupport.queryPort(storeRepository), reportRepository, weeklyReportGenerator,
                IdentityOnboardingApiTestSupport.preferencesQueryPort(tenantPreferencesRepository),
                new WeeklyReportWindowResolver(reportRepository), fixedClockAt(SUNDAY_DATE, "20:05:00", WAT), modules);
        when(tenantRepository.findAll()).thenReturn(List.of(activeTenant()));
        // a Free tenant: reporting enabled (free), « Rapports avancés » disabled (no row, no premium plan)
        when(modules.moduleStates(TENANT_SCHEMA)).thenReturn(states(com.keevo.shared.module.ModuleState.ENABLED,
                com.keevo.shared.module.ModuleState.DISABLED));

        scheduler.runWeeklyReport();
        verifyNoInteractions(weeklyReportGenerator);
        verifyNoInteractions(tenantPreferencesRepository);
    }

    private static java.util.Map<String, com.keevo.shared.module.ModuleState> states(
            com.keevo.shared.module.ModuleState reporting, com.keevo.shared.module.ModuleState advanced) {
        return java.util.Map.of("reporting", reporting, "advanced-reports", advanced);
    }

    @Test
    void story1310a_review_uninstalledGateModule_tenantProcessedWithWarn() {
        com.keevo.shared.module.ModuleRegistry modules = mock(com.keevo.shared.module.ModuleRegistry.class);
        // nothing installed: definition() empty for every id
        scheduler = new WeeklyReportScheduler(IdentityAuthApiTestSupport.tenantQueryPort(tenantRepository),
                StoreApiTestSupport.queryPort(storeRepository), reportRepository, weeklyReportGenerator,
                IdentityOnboardingApiTestSupport.preferencesQueryPort(tenantPreferencesRepository),
                new WeeklyReportWindowResolver(reportRepository), fixedClockAt(SUNDAY_DATE, "20:05:00", WAT), modules);
        when(tenantRepository.findAll()).thenReturn(List.of(activeTenant()));
        when(modules.moduleStates(TENANT_SCHEMA)).thenReturn(java.util.Map.of());
        when(storeRepository.findAllActive()).thenReturn(List.of(activeStore()));
        when(tenantPreferencesRepository.findByCurrentTenant())
                .thenReturn(Optional.of(prefs(0, "20:00:00", true)));
        when(reportRepository.findByDateAndTenant(any(LocalDate.class), eq(TENANT_SCHEMA), eq(ReportType.WEEKLY)))
                .thenReturn(List.of());
        when(weeklyReportGenerator.generateWeeklyReport(any())).thenReturn(mock(EndOfDayReport.class));

        scheduler.runWeeklyReport();
        verify(weeklyReportGenerator).generateWeeklyReport(any());
    }

    /** A registry mock where every module id is installed (13.10a review: the gate ignores uninstalled ids). */
    static com.keevo.shared.module.ModuleRegistry installedRegistry() {
        com.keevo.shared.module.ModuleRegistry modules = mock(com.keevo.shared.module.ModuleRegistry.class);
        org.mockito.Mockito.lenient().when(modules.definition(org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(inv -> java.util.Optional.of(com.keevo.shared.module.TestModuleCatalogs.loaded(
                        inv.getArgument(0), com.keevo.shared.module.ModuleCategory.BUSINESS, true).manifest()));
        return modules;
    }
}
