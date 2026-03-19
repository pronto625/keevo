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
import java.util.UUID;

/**
 * DayClosureAutoScheduler — Automatic day closure at 20h00 WAT.
 * Story 4.4 — Clôture Journalière & Historique des Ventes
 *
 * <p>Runs at 19:00 UTC (= ~20:00 WAT) for each active tenant.
 * For each store without a closure today, triggers automatic closure.
 *
 * <p>GoF Strategy: uses isAutomatic=true which triggers AutoReportStrategy in listener.
 */
@Component
public class DayClosureAutoScheduler {

    private static final Logger log = LoggerFactory.getLogger(DayClosureAutoScheduler.class);
    private static final UUID SYSTEM_UUID = UUID.fromString("00000000-0000-0000-0000-000000000000");

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
     * Scheduled task: runs at 19:00 UTC = ~20:00 WAT.
     * Pattern: 0 0 19 * * * = second 0, minute 0, hour 19, every day.
     */
    @Scheduled(cron = "0 0 19 * * *")
    public void runAutoClosure() {
        log.info("DayClosureAutoScheduler: starting automatic closure check");

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
