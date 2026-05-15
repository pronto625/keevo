package com.keevo.commerce.sale.application.service;

import com.keevo.commerce.sale.domain.port.in.CloseDayUseCase;
import com.keevo.commerce.sale.domain.port.in.CloseDayUseCase.CloseDayCommand;
import com.keevo.commerce.sale.domain.port.out.DayClosureRepository;
import com.keevo.identity.auth.domain.model.Tenant;
import com.keevo.identity.auth.domain.model.TenantStatus;
import com.keevo.identity.auth.domain.port.out.TenantRepository;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.store.store.domain.port.out.StoreRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

/**
 * DayClosureAutoScheduler — Per-tenant automatic day closure.
 * Story 4.4 — Clôture Journalière & Historique des Ventes
 * Story 7.6 — Fires exactly at 23:00 UTC (= 00:00 WAT); no per-tenant time check needed.
 *
 * <p>GoF Strategy: uses isAutomatic=true which triggers AutoReportStrategy in listener.
 * CloseDayService will derive reportDate = LocalDate.now(WAT).minusDays(1) for automatic closures.
 */
@Component
public class DayClosureAutoScheduler {

    private static final Logger log = LoggerFactory.getLogger(DayClosureAutoScheduler.class);
    private static final UUID SYSTEM_UUID = UUID.fromString("00000000-0000-0000-0000-000000000000");
    private static final ZoneId WAT = ZoneId.of("Africa/Lagos");

    private final TenantRepository tenantRepository;
    private final StoreRepository storeRepository;
    private final DayClosureRepository dayClosureRepository;
    private final CloseDayUseCase closeDayUseCase;

    public DayClosureAutoScheduler(TenantRepository tenantRepository,
                                   StoreRepository storeRepository,
                                   DayClosureRepository dayClosureRepository,
                                   CloseDayUseCase closeDayUseCase) {
        this.tenantRepository = tenantRepository;
        this.storeRepository = storeRepository;
        this.dayClosureRepository = dayClosureRepository;
        this.closeDayUseCase = closeDayUseCase;
    }

    /**
     * Fires at 23:00 UTC = 00:00 WAT daily.
     * Pattern: 0 0 23 * * * = second 0, minute 0, hour 23, every day.
     */
    @Scheduled(cron = "0 0 23 * * *")
    public void runAutoClosure() {
        log.info("DayClosureAutoScheduler: starting automatic midnight closure");

        for (Tenant tenant : tenantRepository.findAll()) {
            if (tenant.getStatus() != TenantStatus.ACTIVE) {
                log.debug("Skipping inactive tenant: {}", tenant.getSchemaName());
                continue;
            }

            try {
                TenantContext.setCurrentTenant(tenant.getSchemaName());
                processTenantsStores(tenant);
            } catch (Exception e) {
                log.error("Error processing tenant {}: {}", tenant.getSchemaName(), e.getMessage(), e);
            } finally {
                TenantContext.clear();
            }
        }

        log.info("DayClosureAutoScheduler: completed");
    }

    private void processTenantsStores(Tenant tenant) {
        var stores = storeRepository.findAllActive();
        // Fix H2: scheduler fires at 00:00 WAT (day D) but closes day D-1.
        // Check against D-1 so manual closures of D-1 are correctly detected.
        LocalDate closureDate = LocalDate.now(WAT).minusDays(1);

        for (var store : stores) {
            if (dayClosureRepository.existsByStoreIdAndDate(store.id(), closureDate)) {
                log.debug("Store {} already closed for {}", store.id(), closureDate);
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

