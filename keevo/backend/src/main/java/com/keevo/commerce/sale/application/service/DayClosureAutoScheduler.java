package com.keevo.commerce.sale.application.service;

import com.keevo.commerce.sale.domain.port.in.CloseDayUseCase;
import com.keevo.commerce.sale.domain.port.in.CloseDayUseCase.CloseDayCommand;
import com.keevo.commerce.sale.domain.port.out.DayClosureRepository;
import com.keevo.identity.auth.domain.model.Tenant;
import com.keevo.identity.auth.domain.model.TenantStatus;
import com.keevo.identity.auth.domain.port.out.TenantRepository;
import com.keevo.identity.onboarding.domain.model.TenantPreferences;
import com.keevo.identity.onboarding.domain.port.out.TenantPreferencesRepository;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.store.store.domain.port.out.StoreRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.UUID;

/**
 * DayClosureAutoScheduler — Per-tenant automatic day closure.
 * Story 4.4 — Clôture Journalière & Historique des Ventes
 * Story 7.6 — Hourly cron, per-tenant eodReportTime (via TenantPreferencesRepository)
 * Story 13.3 — Fix: hourly cron + per-tenant eodReportTime + fallback 20h WAT (Option A).
 *
 * <p>Runs every hour (same cron as WeeklyReportScheduler).
 * Per-tenant eodReportTime and eodReportEnabled are read live from
 * tenant_preferences on each tick — no restart needed after config change.
 *
 * <p>GoF Strategy: uses isAutomatic=true which triggers AutoReportStrategy in listener.
 * CloseDayService will derive reportDate = LocalDate.now(WAT) for automatic closures
 * (Story 13.3: no more minusDays(1) — scheduler fires at configured time, closes current day).
 *
 * <p>Clock injection via package-private constructor for deterministic unit tests.
 */
@Component
public class DayClosureAutoScheduler {

    private static final Logger log = LoggerFactory.getLogger(DayClosureAutoScheduler.class);
    private static final UUID SYSTEM_UUID = UUID.fromString("00000000-0000-0000-0000-000000000000");
    private static final ZoneId WAT = ZoneId.of("Africa/Lagos");
    private static final LocalTime DEFAULT_EOD_TIME = LocalTime.of(20, 0);

    private final TenantRepository tenantRepository;
    private final StoreRepository storeRepository;
    private final DayClosureRepository dayClosureRepository;
    private final CloseDayUseCase closeDayUseCase;
    private final TenantPreferencesRepository tenantPreferencesRepository;
    private final Clock clock;

    /** Production constructor — Spring calls this; system clock is used. */
    @Autowired
    public DayClosureAutoScheduler(TenantRepository tenantRepository,
                                   StoreRepository storeRepository,
                                   DayClosureRepository dayClosureRepository,
                                   CloseDayUseCase closeDayUseCase,
                                   TenantPreferencesRepository tenantPreferencesRepository) {
        this(tenantRepository, storeRepository, dayClosureRepository,
             closeDayUseCase, tenantPreferencesRepository, Clock.systemDefaultZone());
    }

    /** Package-private constructor for tests with an injected clock. */
    DayClosureAutoScheduler(TenantRepository tenantRepository,
                            StoreRepository storeRepository,
                            DayClosureRepository dayClosureRepository,
                            CloseDayUseCase closeDayUseCase,
                            TenantPreferencesRepository tenantPreferencesRepository,
                            Clock clock) {
        this.tenantRepository = tenantRepository;
        this.storeRepository = storeRepository;
        this.dayClosureRepository = dayClosureRepository;
        this.closeDayUseCase = closeDayUseCase;
        this.tenantPreferencesRepository = tenantPreferencesRepository;
        this.clock = clock;
    }

    /**
     * Hourly tick — reads per-tenant eodReportTime and eodReportEnabled live.
     * Pattern: 0 0 * * * * = second 0, minute 0, every hour.
     */
    @Scheduled(cron = "0 0 * * * *")
    public void runAutoClosure() {
        LocalDate todayWAT = LocalDate.now(clock.withZone(WAT));
        LocalTime nowWAT   = LocalTime.now(clock.withZone(WAT));

        tenantRepository.findAll().forEach(tenant -> {
            if (tenant.getStatus() != TenantStatus.ACTIVE) {
                return;
            }
            TenantContext.setCurrentTenant(tenant.getSchemaName());
            try {
                processTenant(tenant, todayWAT, nowWAT);
            } catch (Exception e) {
                log.error("DayClosureAutoScheduler error for tenant={}: {}", tenant.getSchemaName(), e.getMessage(), e);
            } finally {
                TenantContext.clear();
            }
        });
    }

    private void processTenant(Tenant tenant, LocalDate todayWAT, LocalTime nowWAT) {
        TenantPreferences prefs = tenantPreferencesRepository.findByCurrentTenant().orElse(null);

        boolean enabled     = prefs != null ? prefs.eodReportEnabled() : true;
        LocalTime triggerAt = prefs != null ? parseTime(prefs.eodReportTime()) : DEFAULT_EOD_TIME;

        if (!enabled) {
            log.debug("EOD auto-close disabled for tenant={}", tenant.getSchemaName());
            return;
        }
        if (nowWAT.isBefore(triggerAt)) {
            return;
        }

        // closureDate = todayWAT (Story 13.3: closes the day that ends at configured time)
        storeRepository.findAllActive().forEach(store -> {
            if (dayClosureRepository.existsByStoreIdAndDate(store.id(), todayWAT)) {
                log.debug("Store {} already closed for {}", store.id(), todayWAT);
                return;
            }

            log.info("Auto-closing store {} for tenant {} at {} WAT", store.id(), tenant.getSchemaName(), nowWAT);
            try {
                closeDayUseCase.closeDay(new CloseDayCommand(
                        store.id(),
                        SYSTEM_UUID,
                        tenant.getSchemaName(),
                        true  // isAutomatic = true
                ));
            } catch (Exception e) {
                log.error("Failed to auto-close store {}: {}", store.id(), e.getMessage());
            }
        });
    }

    private LocalTime parseTime(String hhMmSs) {
        if (hhMmSs == null) return DEFAULT_EOD_TIME;
        try {
            return LocalTime.parse(hhMmSs);
        } catch (Exception e) {
            log.warn("Invalid eodReportTime '{}', falling back to 20:00", hhMmSs);
            return DEFAULT_EOD_TIME;
        }
    }
}

