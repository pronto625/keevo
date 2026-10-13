package com.keevo.shared.infrastructure.scheduling;

import com.keevo.admin.alerts.domain.port.in.SendSyncSilenceNoticesUseCase;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * SyncSilenceAlertScheduler — daily 10:00 WAT WhatsApp to owners whose devices have all been silent
 * for 5 days or more (Story 9-5, FR81). ShedLock: one instance per tick.
 */
@Component
public class SyncSilenceAlertScheduler {

    private static final Logger log = LoggerFactory.getLogger(SyncSilenceAlertScheduler.class);

    private final SendSyncSilenceNoticesUseCase sendSyncSilenceNoticesUseCase;

    public SyncSilenceAlertScheduler(SendSyncSilenceNoticesUseCase sendSyncSilenceNoticesUseCase) {
        this.sendSyncSilenceNoticesUseCase = sendSyncSilenceNoticesUseCase;
    }

    @Scheduled(cron = "${keevo.sync-silence-alert.cron:0 0 10 * * *}", zone = "Africa/Douala")
    @SchedulerLock(name = "syncSilenceAlertScheduler.sendNotices", lockAtMostFor = "PT2H", lockAtLeastFor = "PT1M")
    public void sendNotices() {
        try {
            int count = sendSyncSilenceNoticesUseCase.execute();
            log.info("[SyncSilenceAlertScheduler] {} notice(s) sent.", count);
        } catch (Exception e) {
            log.error("[SyncSilenceAlertScheduler] Error during notice run: {}", e.getMessage(), e);
        }
    }
}
