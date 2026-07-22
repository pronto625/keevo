package com.keevo.reporting.report.application.service;

import com.keevo.identity.auth.domain.model.TenantStatus;
import com.keevo.identity.auth.domain.port.out.TenantRepository;
import com.keevo.identity.onboarding.domain.model.TenantPreferences;
import com.keevo.identity.onboarding.domain.port.out.TenantPreferencesRepository;
import com.keevo.reporting.report.domain.model.ReportType;
import com.keevo.reporting.report.domain.port.in.GenerateWeeklyReportUseCase;
import com.keevo.reporting.report.domain.port.in.GenerateWeeklyReportUseCase.WeeklyReportCommand;
import com.keevo.reporting.report.domain.port.out.EndOfDayReportRepository;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.store.store.domain.port.out.StoreRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;

/**
 * WeeklyReportScheduler — hourly tick that triggers weekly reports per tenant.
 * Story 7.3 — Rapport Hebdomadaire Automatique (AC1).
 *
 * <p>Runs every hour (same cron as DayClosureAutoScheduler).
 * Per-tenant weeklyReportDay (0=Sun…6=Sat) and weeklyReportTime are read live from
 * tenant_preferences on each tick — no restart needed after config change.
 *
 * <p>Guard: if a WEEKLY report already exists for todayWAT in reportRepository, skip.
 *
 * <p>Clock injection via package-private constructor for deterministic unit tests.
 */
@Component
public class WeeklyReportScheduler {

    private static final Logger log = LoggerFactory.getLogger(WeeklyReportScheduler.class);
    private static final ZoneId WAT = ZoneId.of("Africa/Lagos");
    private static final LocalTime DEFAULT_REPORT_TIME = LocalTime.of(20, 0);
    private static final int DEFAULT_REPORT_DAY = 0; // Sunday

    private final TenantRepository tenantRepository;
    private final StoreRepository storeRepository;
    private final EndOfDayReportRepository reportRepository;
    private final GenerateWeeklyReportUseCase weeklyReportGenerator;
    private final TenantPreferencesRepository tenantPreferencesRepository;
    private final Clock clock;

    /** Production constructor — Spring calls this; system clock is used. */
    @Autowired
    public WeeklyReportScheduler(TenantRepository tenantRepository,
                                  StoreRepository storeRepository,
                                  EndOfDayReportRepository reportRepository,
                                  GenerateWeeklyReportUseCase weeklyReportGenerator,
                                  TenantPreferencesRepository tenantPreferencesRepository) {
        this(tenantRepository, storeRepository, reportRepository,
             weeklyReportGenerator, tenantPreferencesRepository, Clock.systemDefaultZone());
    }

    /** Package-private constructor for tests with an injected clock. */
    WeeklyReportScheduler(TenantRepository tenantRepository,
                           StoreRepository storeRepository,
                           EndOfDayReportRepository reportRepository,
                           GenerateWeeklyReportUseCase weeklyReportGenerator,
                           TenantPreferencesRepository tenantPreferencesRepository,
                           Clock clock) {
        this.tenantRepository = tenantRepository;
        this.storeRepository = storeRepository;
        this.reportRepository = reportRepository;
        this.weeklyReportGenerator = weeklyReportGenerator;
        this.tenantPreferencesRepository = tenantPreferencesRepository;
        this.clock = clock;
    }

    @Scheduled(cron = "0 0 * * * *")
    public void runWeeklyReport() {
        LocalDate todayWAT = LocalDate.now(clock.withZone(WAT));
        LocalTime nowWAT   = LocalTime.now(clock.withZone(WAT));

        tenantRepository.findAll().forEach(tenant -> {
            if (tenant.getStatus() != TenantStatus.ACTIVE) {
                return;
            }
            TenantContext.setCurrentTenant(tenant.getSchemaName());
            try {
                processTenant(tenant.getSchemaName(), todayWAT, nowWAT);
            } catch (Exception e) {
                log.error("WeeklyReportScheduler error for tenant={}: {}", tenant.getSchemaName(), e.getMessage(), e);
            } finally {
                TenantContext.clear();
            }
        });
    }

    private void processTenant(String tenantId, LocalDate todayWAT, LocalTime nowWAT) {
        TenantPreferences prefs = tenantPreferencesRepository.findByCurrentTenant().orElse(null);

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

        // Guard: skip if already generated for this week's Sunday
        if (!reportRepository.findByDateAndTenant(todayWAT, tenantId, ReportType.WEEKLY).isEmpty()) {
            log.debug("WeeklyReport already generated for tenant={} date={}", tenantId, todayWAT);
            return;
        }

        var channel = prefs != null ? prefs.weeklyReportChannel() : null;

        // Monday 00:00:00 WAT → Sunday 23:59:59 WAT (ISO week, invariant regardless of trigger day)
        // Mirror ReportController.triggerWeekly pattern: todayWAT.with(DayOfWeek.SUNDAY).minusDays(6)
        LocalDate weekEndLocal   = todayWAT.with(DayOfWeek.SUNDAY);
        LocalDate weekStartLocal = weekEndLocal.minusDays(6);
        Instant weekStart = weekStartLocal.atStartOfDay(WAT).toInstant();
        Instant weekEnd   = weekEndLocal.atTime(23, 59, 59).atZone(WAT).toInstant();

        storeRepository.findAllActive().forEach(store -> {
            try {
                var cmd = new WeeklyReportCommand(
                        store.id(), tenantId, true, weekStart, weekEnd, channel);
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
