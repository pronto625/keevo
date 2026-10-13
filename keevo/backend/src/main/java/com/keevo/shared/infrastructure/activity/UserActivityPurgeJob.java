package com.keevo.shared.infrastructure.activity;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;

/**
 * UserActivityPurgeJob — daily deletion of {@code public.user_daily_activity} rows older than
 * {@value #RETENTION_DAYS} days (Story 9-4). The growth curves read up to 24 calendar months back
 * ({@code /analytics/growth?months=24}, MAU per month), so 760 days keep 24 full months plus margin.
 * One runner across instances (ShedLock).
 */
@Component
public class UserActivityPurgeJob {

    private static final Logger log = LoggerFactory.getLogger(UserActivityPurgeJob.class);

    static final int RETENTION_DAYS = 760;
    static final String PURGE_SQL = "DELETE FROM public.user_daily_activity WHERE activity_date < ?";

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public UserActivityPurgeJob(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Scheduled(cron = "${keevo.user-activity-purge.cron:0 30 3 * * *}", zone = "Africa/Douala")
    @SchedulerLock(name = "userActivityPurgeJob.purge", lockAtMostFor = "PT2H", lockAtLeastFor = "PT1M")
    public void purge() {
        LocalDate cutoff = LocalDate.now(clock.withZone(UserActivityRecorder.WAT)).minusDays(RETENTION_DAYS);
        try {
            int deleted = jdbc.update(PURGE_SQL, cutoff);
            if (deleted > 0) {
                log.info("User activity purge: {} row(s) older than {} deleted", deleted, cutoff);
            }
        } catch (RuntimeException e) {
            log.warn("User activity purge failed ({})", e.getClass().getSimpleName());
        }
    }
}
