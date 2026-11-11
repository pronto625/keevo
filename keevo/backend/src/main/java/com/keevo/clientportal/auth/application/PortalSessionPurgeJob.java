package com.keevo.clientportal.auth.application;

import com.keevo.clientportal.auth.domain.port.out.PortalSessionRepository;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;

/**
 * Story 21.7 — nightly removal of the expired client portal sessions (an expired session is already refused; this
 * keeps the table small). One set-based DELETE on {@code idx_portal_sessions_expires_at}; one runner across instances
 * (ShedLock).
 */
@Component
public class PortalSessionPurgeJob {

    private static final Logger log = LoggerFactory.getLogger(PortalSessionPurgeJob.class);

    private final PortalSessionRepository sessions;
    private final Clock clock;

    public PortalSessionPurgeJob(PortalSessionRepository sessions, Clock clock) {
        this.sessions = sessions;
        this.clock = clock;
    }

    @Scheduled(cron = "${keevo.portal-session-purge.cron:0 50 3 * * *}", zone = "Africa/Douala")
    @SchedulerLock(name = "portalSessionPurgeJob.purge", lockAtMostFor = "PT1H", lockAtLeastFor = "PT1M")
    public void purge() {
        try {
            int deleted = sessions.purgeExpiredBefore(clock.instant());
            if (deleted > 0) {
                log.info("Portal session purge: {} expired session(s) deleted", deleted);
            }
        } catch (RuntimeException e) {
            log.warn("Portal session purge failed ({}: {})", e.getClass().getSimpleName(), e.getMessage());
        }
    }
}
