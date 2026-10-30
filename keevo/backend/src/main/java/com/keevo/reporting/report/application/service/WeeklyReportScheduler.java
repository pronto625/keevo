package com.keevo.reporting.report.application.service;

import com.keevo.identity.api.TenantStatus;
import com.keevo.identity.api.TenantQueryPort;
import com.keevo.identity.api.TenantPreferencesView;
import com.keevo.identity.api.TenantPreferencesQueryPort;
import com.keevo.reporting.report.domain.model.ReportType;
import com.keevo.reporting.report.domain.port.in.GenerateWeeklyReportUseCase;
import com.keevo.reporting.report.domain.port.in.GenerateWeeklyReportUseCase.WeeklyReportCommand;
import com.keevo.reporting.report.domain.port.out.EndOfDayReportRepository;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.store.api.StoreQueryPort;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import com.keevo.reporting.module.ReportingModule;
import com.keevo.shared.module.ModuleRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;

/**
 * WeeklyReportScheduler — hourly tick that triggers weekly reports per tenant.
 * Story 7.3 — Rapport Hebdomadaire Automatique (AC1).
 *
 * <p>Runs every hour (same cron as DayClosureAutoScheduler).
 * Per-tenant weeklyReportDay (0=Sun…6=Sat) and weeklyReportTime are read live from
 * tenant_preferences on each tick — no restart needed after config change.
 *
 * <p>Guard: stores that already have a WEEKLY report for the week's Sunday are skipped (per store).
 *
 * <p>Clock injection via package-private constructor for deterministic unit tests.
 */
@Component
public class WeeklyReportScheduler {

    private static final Logger log = LoggerFactory.getLogger(WeeklyReportScheduler.class);
    private static final ZoneId WAT = ZoneId.of("Africa/Lagos");
    private static final LocalTime DEFAULT_REPORT_TIME = LocalTime.of(20, 0);
    private static final int DEFAULT_REPORT_DAY = 0; // Sunday

    private final TenantQueryPort tenantQuery;
    private final StoreQueryPort storeQuery;
    private final EndOfDayReportRepository reportRepository;
    private final GenerateWeeklyReportUseCase weeklyReportGenerator;
    private final TenantPreferencesQueryPort preferencesQuery;
    private final WeeklyReportWindowResolver windowResolver;
    private final Clock clock;
    private final ModuleRegistry modules;

    /** Without module gate (every tenant processed) — unit tests. */
    public WeeklyReportScheduler(TenantQueryPort tenantQuery,
                           StoreQueryPort storeQuery,
                           EndOfDayReportRepository reportRepository,
                           GenerateWeeklyReportUseCase weeklyReportGenerator,
                           TenantPreferencesQueryPort preferencesQuery,
                           WeeklyReportWindowResolver windowResolver,
                           Clock clock) {
        this(tenantQuery, storeQuery, reportRepository, weeklyReportGenerator, preferencesQuery, windowResolver,
                clock, null);
    }

    /**
     * Production constructor — the shared {@code Clock} bean (ClockConfig), as CloseDayService (v1s-13-11
     * follow-up). Story 13.9: tenants whose « Rapports » module is DISABLED are skipped (READ_ONLY still runs).
     */
    @Autowired
    public WeeklyReportScheduler(TenantQueryPort tenantQuery,
                           StoreQueryPort storeQuery,
                           EndOfDayReportRepository reportRepository,
                           GenerateWeeklyReportUseCase weeklyReportGenerator,
                           TenantPreferencesQueryPort preferencesQuery,
                           WeeklyReportWindowResolver windowResolver,
                           Clock clock,
                           ModuleRegistry modules) {
        this.modules = modules;
        this.tenantQuery = tenantQuery;
        this.storeQuery = storeQuery;
        this.reportRepository = reportRepository;
        this.weeklyReportGenerator = weeklyReportGenerator;
        this.preferencesQuery = preferencesQuery;
        this.windowResolver = windowResolver;
        this.clock = clock;
    }

    @Scheduled(cron = "0 0 * * * *")
    @SchedulerLock(name = "weeklyReportScheduler.runWeeklyReport", lockAtMostFor = "PT50M", lockAtLeastFor = "PT1M")
    public void runWeeklyReport() {
        LocalDate todayWAT = LocalDate.now(clock.withZone(WAT));
        LocalTime nowWAT   = LocalTime.now(clock.withZone(WAT));

        tenantQuery.findAll().forEach(tenant -> {
            if (tenant.status() != TenantStatus.ACTIVE) {
                return;
            }
            if (ReportingModuleGate.disabled(modules, tenant.schemaName(), "WeeklyReportScheduler",
                    ReportingModule.ID, ReportingModuleGate.ADVANCED_REPORTS)) {
                return;
            }
            TenantContext.setCurrentTenant(tenant.schemaName());
            try {
                processTenant(tenant.schemaName(), todayWAT, nowWAT);
            } catch (Exception e) {
                log.error("WeeklyReportScheduler error for tenant={}: {}", tenant.schemaName(), e.getMessage(), e);
            } finally {
                TenantContext.clear();
            }
        });
    }

    private void processTenant(String tenantId, LocalDate todayWAT, LocalTime nowWAT) {
        TenantPreferencesView prefs = preferencesQuery.find(TenantContext.getCurrentTenant()).orElse(null);

        boolean enabled     = prefs != null ? prefs.weeklyReportEnabled() : true;
        int configuredDay   = prefs != null ? prefs.weeklyReportDay() : DEFAULT_REPORT_DAY;
        LocalTime triggerAt = prefs != null ? parseTime(prefs.weeklyReportTime()) : DEFAULT_REPORT_TIME;

        if (!enabled) {
            log.debug("WeeklyReport disabled for tenant={}", tenantId);
            return;
        }

        DayOfWeek configuredDow = toDayOfWeek(configuredDay);
        if (todayWAT.getDayOfWeek() != configuredDow) {
            return;
        }
        if (nowWAT.isBefore(triggerAt)) {
            return;
        }

        // Last completed ISO week, its Sunday being today or the one before. (with(SUNDAY)
        // would pick the coming Sunday and report the current partial week whenever the
        // configured day is not Sunday.) Mirror ReportController.triggerWeekly.
        LocalDate weekEndLocal   = todayWAT.with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY));

        // Guard, per store: skip the stores that already have that week's report (reports are
        // dated by their Sunday). Per store so a store whose generation failed is retried on the
        // next tick even though the others succeeded (v1s-13-11: keeps the D2 chain intact).
        var generatedStoreIds = reportRepository.findByDateAndTenant(weekEndLocal, tenantId, ReportType.WEEKLY)
                .stream().map(r -> r.getStoreId()).collect(java.util.stream.Collectors.toSet());

        var channel = prefs != null ? prefs.weeklyReportChannel() : null;

        Instant now = Instant.now(clock);

        storeQuery.findAllActive(TenantContext.getCurrentTenant()).forEach(store -> {
            if (generatedStoreIds.contains(store.id())) {
                log.debug("WeeklyReport already generated for tenant={} store={} week ending {}",
                        tenantId, store.id(), weekEndLocal);
                return;
            }
            try {
                // v1s-13-11 (D2): the window chains on the store's previous weekly report, so
                // the Sunday evening after a 20:00 report and late offline sales are not lost.
                var window = windowResolver.resolve(store.id(), tenantId, weekEndLocal, now);
                var cmd = new WeeklyReportCommand(
                        store.id(), tenantId, true, window.start(), window.end(), channel,
                        window.lateFrom(), window.receivedUntil());
                weeklyReportGenerator.generateWeeklyReport(cmd);
                log.info("WeeklyReport generated for tenant={} store={}", tenantId, store.id());
            } catch (Exception e) {
                log.error("Failed to generate weekly report for store={}: {}", store.id(), e.getMessage(), e);
            }
        });
    }

    /**
     * Maps weeklyReportDay (0=Sunday … 6=Saturday) to Java DayOfWeek.
     * Java DayOfWeek.of() uses ISO: 1=Monday … 7=Sunday.
     */
    private DayOfWeek toDayOfWeek(int day) {
        if (day == 0) return DayOfWeek.SUNDAY;
        return DayOfWeek.of(day);
    }

    // Defensive only: weekly_report_time is a TIME column and writes are validated (HH:mm:ss,
    // 422 VALIDATION_ERROR on PUT /tenant/report-preferences), so the fallback never fires
    // on stored data.
    private LocalTime parseTime(String hhMmSs) {
        if (hhMmSs == null) return DEFAULT_REPORT_TIME;
        try {
            return LocalTime.parse(hhMmSs);
        } catch (Exception e) {
            log.warn("Invalid weeklyReportTime '{}', falling back to 20:00", hhMmSs);
            return DEFAULT_REPORT_TIME;
        }
    }
}
