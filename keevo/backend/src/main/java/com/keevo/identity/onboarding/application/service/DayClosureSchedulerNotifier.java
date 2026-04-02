package com.keevo.identity.onboarding.application.service;

import com.keevo.identity.onboarding.domain.model.PreferenceUpdatedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * DayClosureSchedulerNotifier — Observer that reacts to preference updates.
 *
 * <p>Currently logs the event for observability. The DayClosureAutoScheduler reads
 * preferences live on each hourly tick, so no in-memory state synchronisation is needed.
 * This component is kept as the single integration point for future scheduler adaptations
 * (e.g. dynamic cron reschedule) without coupling the scheduler to the preferences service.
 */
@Component
public class DayClosureSchedulerNotifier {

    private static final Logger log = LoggerFactory.getLogger(DayClosureSchedulerNotifier.class);

    @EventListener
    public void onPreferenceUpdated(PreferenceUpdatedEvent event) {
        log.info("[Scheduler] Tenant {} updated EOD report time to {}. Scheduler will adapt on next tick.",
                event.tenantId(), event.newEodReportTime());
    }
}
