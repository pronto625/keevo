package com.keevo.subscription.plan.adapter.in.scheduling;

import com.keevo.shared.capability.entitlement.QuotaPeriod;
import com.keevo.shared.domain.time.KeevoZones;
import com.keevo.subscription.plan.domain.port.out.UsageCounterStore;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;

/**
 * Story 19.8 — nightly purge of old usage counters ({@code public.usage_counters}): DAY counters older than
 * {@value #DAY_RETENTION_DAYS} days and MONTH counters older than {@value #MONTH_RETENTION_MONTHS} months; LIFETIME
 * counters are kept. One set-based DELETE per period kind (no per-tenant loop); one runner across instances
 * (ShedLock), days of the business zone (WAT).
 */
@Component
public class UsageCounterPurgeJob {

    private static final Logger log = LoggerFactory.getLogger(UsageCounterPurgeJob.class);

    static final int DAY_RETENTION_DAYS = 40;
    static final int MONTH_RETENTION_MONTHS = 25;

    private final UsageCounterStore store;
    private final Clock clock;

    public UsageCounterPurgeJob(UsageCounterStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    @Scheduled(cron = "${keevo.usage-counter-purge.cron:0 40 3 * * *}", zone = "Africa/Douala")
    @SchedulerLock(name = "usageCounterPurgeJob.purge", lockAtMostFor = "PT1H", lockAtLeastFor = "PT1M")
    public void purge() {
        LocalDate today = LocalDate.ofInstant(clock.instant(), KeevoZones.WAT);
        String dayCutoff = QuotaPeriod.DAY.key(today.minusDays(DAY_RETENTION_DAYS));
        String monthCutoff = QuotaPeriod.MONTH.key(today.minusMonths(MONTH_RETENTION_MONTHS));
        try {
            int deleted = store.purge(dayCutoff, monthCutoff);
            if (deleted > 0) {
                log.info("Usage counter purge: {} row(s) deleted (days before {}, months before {})", deleted,
                        dayCutoff, monthCutoff);
            }
        } catch (RuntimeException e) {
            log.warn("Usage counter purge failed ({}: {})", e.getClass().getSimpleName(), e.getMessage());
        }
    }
}
