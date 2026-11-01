package com.keevo.shared.module.adapter.in.scheduling;

import com.keevo.shared.module.application.ModuleExpiryMaterializer;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Story 16.1 — nightly materialisation of module licence statuses ({@link ModuleExpiryMaterializer}), at 02:30
 * Douala time: after the 02:00 plan downgrade ({@code SubscriptionExpiryScheduler}), so plan-derived rows are already
 * up to date. One instance per tick (ShedLock). Bookkeeping only: blocking never depends on this job having run.
 */
@Component
public class ModuleExpiryScheduler {

    private static final Logger log = LoggerFactory.getLogger(ModuleExpiryScheduler.class);

    private final ModuleExpiryMaterializer materializer;

    public ModuleExpiryScheduler(ModuleExpiryMaterializer materializer) {
        this.materializer = materializer;
    }

    @Scheduled(cron = "${keevo.module-expiry.cron:0 30 2 * * *}", zone = "Africa/Douala")
    @SchedulerLock(name = "moduleExpiryScheduler.materialize", lockAtMostFor = "PT2H", lockAtLeastFor = "PT1M")
    public void materialize() {
        try {
            materializer.materialize();
        } catch (RuntimeException e) {
            log.error("Module licences: nightly run failed ({})", e.getClass().getSimpleName(), e);
        }
    }
}
