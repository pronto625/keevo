package com.keevo.subscription.plan.adapter.in.scheduling;

import com.keevo.subscription.plan.domain.port.in.SendPlanExpiryRemindersUseCase;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * PlanExpiryReminderScheduler — daily 08:00 WAT (after the 02:00 expiry downgrade) WhatsApp
 * reminders for PREMIUM plans expiring in 7 or 1 days (Story 9-2). ShedLock: one instance per tick.
 */
@Component
public class PlanExpiryReminderScheduler {

    private static final Logger log = LoggerFactory.getLogger(PlanExpiryReminderScheduler.class);

    private final SendPlanExpiryRemindersUseCase sendPlanExpiryRemindersUseCase;

    public PlanExpiryReminderScheduler(SendPlanExpiryRemindersUseCase sendPlanExpiryRemindersUseCase) {
        this.sendPlanExpiryRemindersUseCase = sendPlanExpiryRemindersUseCase;
    }

    @Scheduled(cron = "${keevo.plan-expiry-reminder.cron:0 0 8 * * *}", zone = "Africa/Douala")
    @SchedulerLock(name = "planExpiryReminderScheduler.sendReminders", lockAtMostFor = "PT2H", lockAtLeastFor = "PT1M")
    public void sendReminders() {
        try {
            int count = sendPlanExpiryRemindersUseCase.execute();
            log.info("[PlanExpiryReminderScheduler] {} reminder(s) sent.", count);
        } catch (Exception e) {
            log.error("[PlanExpiryReminderScheduler] Error during reminder run: {}", e.getMessage(), e);
        }
    }
}
