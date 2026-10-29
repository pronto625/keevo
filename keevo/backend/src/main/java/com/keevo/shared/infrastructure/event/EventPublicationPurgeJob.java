package com.keevo.shared.infrastructure.event;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * EventPublicationPurgeJob — daily purge of the event publication registry (Story 12.4).
 *
 * <p>{@code completion-mode=update} keeps completed publications for inspection; rows completed more than
 * {@link #RETENTION} (7 days) ago are deleted. {@code @DeliverOnce} receipts are kept
 * {@link EventListenerReceipts#RETENTION} (30 days, see there). Incomplete publications are never purged.
 * One instance per tick (ShedLock). Each purge logs its deleted row count; a failure is logged with its stack
 * trace and does not stop the other purge.
 */
@Component
public class EventPublicationPurgeJob {

    private static final Logger log = LoggerFactory.getLogger(EventPublicationPurgeJob.class);
    static final Duration RETENTION = Duration.ofDays(7);

    private final EventPublicationMaintenance maintenance;
    private final EventListenerReceipts receipts;
    private final Clock clock;

    public EventPublicationPurgeJob(EventPublicationMaintenance maintenance, EventListenerReceipts receipts,
                                    Clock clock) {
        this.maintenance = maintenance;
        this.receipts = receipts;
        this.clock = clock;
    }

    @Scheduled(cron = "0 50 3 * * *", zone = "Africa/Douala")
    @SchedulerLock(name = "eventPublicationPurgeJob.purge", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    public void purge() {
        Instant now = clock.instant();
        try {
            int deleted = maintenance.deleteCompletedBefore(now.minus(RETENTION));
            log.info("[EventPublicationPurgeJob] {} completed publication(s) older than {} deleted", deleted, RETENTION);
        } catch (Exception e) {
            log.warn("[EventPublicationPurgeJob] Publication purge failed", e);
        }
        try {
            int deleted = receipts.purgeOlderThan(EventListenerReceipts.RETENTION);
            log.info("[EventPublicationPurgeJob] {} delivery receipt(s) older than {} deleted",
                    deleted, EventListenerReceipts.RETENTION);
        } catch (Exception e) {
            log.warn("[EventPublicationPurgeJob] Receipt purge failed", e);
        }
    }
}
