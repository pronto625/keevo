package com.keevo.shared.infrastructure.scheduling;

import com.keevo.subscription.plan.domain.port.in.DowngradeExpiredTrialsCommand;
import com.keevo.subscription.plan.domain.port.in.DowngradeExpiredTrialsUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * SubscriptionExpiryScheduler — nightly job that auto-downgrades expired trials/premiums.
 *
 * <p>Runs daily at 02:00 server time (configurable via cron expression).
 * Delegates to {@link DowngradeExpiredTrialsUseCase} which iterates all tenant schemas
 * via JDBC and updates expired PREMIUM_TRIAL / PREMIUM records to FREE.
 *
 * <p>Design notes:
 * <ul>
 *   <li>Natural expiry always downgrades to FREE — never suspends (AC4).</li>
 *   <li>SUSPENDED status is reserved for manual admin lockout only.</li>
 *   <li>Single-instance safe; for multi-instance, add @SchedulerLock (ShedLock).</li>
 * </ul>
 */
@Component
public class SubscriptionExpiryScheduler {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionExpiryScheduler.class);

    private final DowngradeExpiredTrialsUseCase downgradeExpiredTrialsUseCase;

    public SubscriptionExpiryScheduler(DowngradeExpiredTrialsUseCase downgradeExpiredTrialsUseCase) {
        this.downgradeExpiredTrialsUseCase = downgradeExpiredTrialsUseCase;
    }

    /**
     * Runs daily at 02:00 — downgrades all expired PREMIUM_TRIAL/PREMIUM subscriptions to FREE.
     */
    @Scheduled(cron = "0 0 2 * * *")
    public void downgradeExpiredSubscriptions() {  // L2 fix: was downgradExpiredSubscriptions (typo)
        log.info("[SubscriptionExpiryScheduler] Starting nightly subscription expiry check...");
        try {
            int count = downgradeExpiredTrialsUseCase.execute(new DowngradeExpiredTrialsCommand("scheduler"));
            log.info("[SubscriptionExpiryScheduler] Downgraded {} expired subscription(s) to FREE.", count);
        } catch (Exception e) {
            log.error("[SubscriptionExpiryScheduler] Error during expiry check: {}", e.getMessage(), e);
        }
    }
}
