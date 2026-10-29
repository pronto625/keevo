package com.keevo.shared.infrastructure.event;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.IncompleteEventPublications;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * EventPublicationResubmissionJob — retries incomplete event publications every 10 minutes (Story 12.4).
 *
 * <p>Restart replay alone would leave a failed delivery (e.g. WhatsApp provider down, ruling R2) pending
 * until the next deploy. Every tick:
 * <ol>
 *   <li>refreshes the gauges {@code keevo.events.incomplete} (count) and
 *       {@code keevo.events.incomplete.oldest.seconds} (age of the oldest, 0 when none);</li>
 *   <li>logs a WARN naming the listener ids of publications incomplete for more than {@link #ALERT_AFTER};</li>
 *   <li>resubmits the publications older than {@link #RESUBMIT_AFTER} — younger ones may still be queued on
 *       the async executor; a listener still running past that age could be invoked twice, which the
 *       {@code @DeliverOnce} receipts absorb for the message senders.</li>
 * </ol>
 * One instance per tick (ShedLock; production is single-instance).
 */
@Component
public class EventPublicationResubmissionJob {

    private static final Logger log = LoggerFactory.getLogger(EventPublicationResubmissionJob.class);
    static final Duration RESUBMIT_AFTER = Duration.ofMinutes(10);
    static final Duration ALERT_AFTER = Duration.ofHours(24);

    private final IncompleteEventPublications incomplete;
    private final EventPublicationMaintenance maintenance;
    private final Clock clock;
    private final AtomicLong incompleteCount = new AtomicLong();
    private final AtomicLong oldestSeconds = new AtomicLong();

    public EventPublicationResubmissionJob(IncompleteEventPublications incomplete,
                                           EventPublicationMaintenance maintenance,
                                           MeterRegistry registry, Clock clock) {
        this.incomplete = incomplete;
        this.maintenance = maintenance;
        this.clock = clock;
        Gauge.builder("keevo.events.incomplete", incompleteCount, AtomicLong::get)
                .description("Incomplete event publications (registry)")
                .register(registry);
        Gauge.builder("keevo.events.incomplete.oldest.seconds", oldestSeconds, AtomicLong::get)
                .description("Age of the oldest incomplete event publication, 0 when none")
                .baseUnit("seconds")
                .register(registry);
    }

    @Scheduled(cron = "0 */10 * * * *")
    @SchedulerLock(name = "eventPublicationResubmissionJob.resubmit", lockAtMostFor = "PT9M", lockAtLeastFor = "PT30S")
    public void resubmit() {
        Instant now = clock.instant();
        try {
            refreshMetrics(now);
            List<String> stale = maintenance.incompleteListenerIdsPublishedBefore(now.minus(ALERT_AFTER));
            if (!stale.isEmpty()) {
                log.warn("[EVENTS] Publications incomplete for more than {} — listeners: {}", ALERT_AFTER, stale);
            }
        } catch (Exception e) {
            log.warn("[EVENTS] Incomplete publication check failed", e);
        }
        try {
            incomplete.resubmitIncompletePublicationsOlderThan(RESUBMIT_AFTER);
        } catch (Exception e) {
            log.warn("[EVENTS] Resubmission of incomplete publications failed", e);
        }
    }

    void refreshMetrics(Instant now) {
        EventPublicationMaintenance.IncompleteStats stats = maintenance.incompleteStats();
        incompleteCount.set(stats.count());
        oldestSeconds.set(stats.oldest() == null ? 0 : Math.max(0, Duration.between(stats.oldest(), now).toSeconds()));
    }
}
