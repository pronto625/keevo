package com.keevo.admin.notification.adapter.in.scheduling;

import com.keevo.admin.notification.domain.port.in.PurgeGlobalNotificationsUseCase;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** GlobalNotificationPurgeJob — daily purge of the global pushes older than 90 days (Story 9-5). */
@Component
public class GlobalNotificationPurgeJob {

    private static final Logger log = LoggerFactory.getLogger(GlobalNotificationPurgeJob.class);

    private final PurgeGlobalNotificationsUseCase purgeUseCase;

    public GlobalNotificationPurgeJob(PurgeGlobalNotificationsUseCase purgeUseCase) {
        this.purgeUseCase = purgeUseCase;
    }

    @Scheduled(cron = "0 40 3 * * *", zone = "Africa/Douala")
    @SchedulerLock(name = "globalNotificationPurgeJob.purge", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    public void purge() {
        try {
            purgeUseCase.purgeOld();
        } catch (Exception e) {
            log.warn("[GlobalNotificationPurgeJob] Purge failed: {}", e.getMessage());
        }
    }
}
