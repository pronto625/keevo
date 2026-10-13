package com.keevo.shared.infrastructure.scheduling;

import com.keevo.admin.notification.domain.port.in.DispatchGlobalNotificationsUseCase;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * GlobalNotificationScheduler — every minute, sends the due global pushes of the super admin
 * (Story 9-5, FR82). ShedLock: one instance per tick; the atomic SCHEDULED → SENDING claim of the
 * dispatcher keeps a row from being sent twice even if two ticks overlap. {@code lockAtMostFor} is 10 min
 * (a large broadcast can outlast a tick; ticks while it runs are skipped).
 */
@Component
public class GlobalNotificationScheduler {

    private static final Logger log = LoggerFactory.getLogger(GlobalNotificationScheduler.class);

    private final DispatchGlobalNotificationsUseCase dispatchUseCase;

    public GlobalNotificationScheduler(DispatchGlobalNotificationsUseCase dispatchUseCase) {
        this.dispatchUseCase = dispatchUseCase;
    }

    @Scheduled(cron = "0 * * * * *")
    @SchedulerLock(name = "globalNotificationScheduler.dispatch", lockAtMostFor = "PT10M", lockAtLeastFor = "PT30S")
    public void dispatch() {
        try {
            int processed = dispatchUseCase.dispatchDue();
            if (processed > 0) log.info("[GlobalNotificationScheduler] {} global push(es) processed.", processed);
        } catch (Exception e) {
            log.error("[GlobalNotificationScheduler] Dispatch failed: {}", e.getMessage(), e);
        }
    }
}
