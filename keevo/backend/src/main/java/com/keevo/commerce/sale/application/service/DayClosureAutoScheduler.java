package com.keevo.commerce.sale.application.service;

import com.keevo.commerce.sale.domain.port.in.CloseDayUseCase;
import com.keevo.commerce.sale.domain.port.in.CloseDayUseCase.CloseDayCommand;
import com.keevo.commerce.sale.domain.port.out.DayClosureRepository;
import com.keevo.identity.auth.domain.model.Tenant;
import com.keevo.identity.auth.domain.model.TenantStatus;
import com.keevo.identity.auth.domain.port.out.TenantRepository;
import com.keevo.identity.onboarding.domain.port.out.TenantPreferencesRepository;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.store.store.domain.port.out.StoreRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import org.springframework.beans.factory.annotation.Autowired;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.UUID;

/**
 * DayClosureAutoScheduler — Per-tenant automatic day closure.
 * Story 4.4 — Clôture Journalière & Historique des Ventes
 * Story 7.5 — Runs hourly; each tenant's configured eodReportTime determines when closure fires.
 *
 * <p>Runs every hour. For each tenant, reads the configured {@code eod_report_time} (WAT).
 * If the current WAT time has not yet reached the configured hour, the tenant is skipped.
 * Default fallback is 20:00 WAT (= 19:00 UTC) to preserve backward compatibility.
 *
 * <p>GoF Strategy: uses isAutomatic=true which triggers AutoReportStrategy in listener.
 */
@Component
public class DayClosureAutoScheduler {

    private static final Logger log = LoggerFactory.getLogger(DayClosureAutoScheduler.class);
    private static final UUID SYSTEM_UUID = UUID.fromString("00000000-0000-0000-0000-000000000000");
    private static final ZoneId WAT = ZoneId.of("Africa/Lagos");
    private static final LocalTime DEFAULT_CLOSURE_TIME = LocalTime.of(20, 0);

    private final TenantRepository tenantRepository;
    private final StoreRepository storeRepository;
    private final DayClosureRepository dayClosureRepository;
    private final CloseDayUseCase closeDayUseCase;
    private final TenantPreferencesRepository tenantPreferencesRepository;
    private final Clock clock;

    @Autowired
    public DayClosureAutoScheduler(TenantRepository tenantRepository,
                                   StoreRepository storeRepository,
                                   DayClosureRepository dayClosureRepository,
                                   CloseDayUseCase closeDayUseCase,
                                   TenantPreferencesRepository tenantPreferencesRepository) {
        this(tenantRepository, storeRepository, dayClosureRepository, closeDayUseCase,
             tenantPreferencesRepository, Clock.systemUTC());
    }

    /** Package-private visible to tests in same module — allows injecting a fixed Clock. */
    public DayClosureAutoScheduler(TenantRepository tenantRepository,
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
     * Scheduled task: runs every hour on the hour.
     * Pattern: 0 0 * * * * = second 0, minute 0, every hour, every day.
     */
    @Scheduled(cron = "0 0 * * * *")
    public void runAutoClosure() {
        log.info("DayClosureAutoScheduler: starting automatic closure check");

        LocalTime nowWAT = clock.instant().atZone(WAT).toLocalTime();

        for (Tenant tenant : tenantRepository.findAll()) {
            if (tenant.getStatus() != TenantStatus.ACTIVE) {
                log.debug("Skipping inactive tenant: {}", tenant.getSchemaName());
                continue;
            }

            try {
                TenantContext.setCurrentTenant(tenant.getSchemaName());

                LocalTime configuredTime = resolveEodTime();

// Only trigger when the current WAT time has reached (or passed) the configured time
                if (nowWAT.isBefore(configuredTime)) {
                    log.debug("Tenant {} eod time {} not yet reached (now WAT={}), skipping.",
                            tenant.getSchemaName(), configuredTime, nowWAT);
                    continue;
                }

                processTenantsStores(tenant);
            } catch (Exception e) {
                log.error("Error processing tenant {}: {}", tenant.getSchemaName(), e.getMessage(), e);
            } finally {
                TenantContext.clear();
            }
        }

        log.info("DayClosureAutoScheduler: completed");
    }

    private LocalTime resolveEodTime() {
        return tenantPreferencesRepository.findByCurrentTenant()
                .map(prefs -> {
                    try {
                        return LocalTime.parse(prefs.eodReportTime());
                    } catch (Exception e) {
                        return DEFAULT_CLOSURE_TIME;
                    }
                })
                .orElse(DEFAULT_CLOSURE_TIME);
    }

    private void processTenantsStores(Tenant tenant) {
        var stores = storeRepository.findAllActive();
        LocalDate today = LocalDate.now();

        for (var store : stores) {
            if (dayClosureRepository.existsByStoreIdAndDate(store.id(), today)) {
                log.debug("Store {} already closed for {}", store.id(), today);
                continue;
            }

            log.info("Auto-closing store {} for tenant {}", store.id(), tenant.getSchemaName());
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
        }
    }
}

