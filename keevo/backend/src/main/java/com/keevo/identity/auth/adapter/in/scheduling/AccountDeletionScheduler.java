package com.keevo.identity.auth.adapter.in.scheduling;

import com.keevo.identity.auth.application.service.TenantDeletionService;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * AccountDeletionScheduler — daily trigger of the definitive tenant deletions (Story 14.5, FR91).
 *
 * <p>Runs daily at 03:00 server time (offset from the subscription expiry run at 02:00).
 * ShedLock: one instance per tick. The routine itself (per-tenant transaction, WhatsApp after
 * commit) lives in {@link TenantDeletionService}.
 */
@Component
public class AccountDeletionScheduler {

    private final TenantDeletionService tenantDeletionService;

    public AccountDeletionScheduler(TenantDeletionService tenantDeletionService) {
        this.tenantDeletionService = tenantDeletionService;
    }

    @Scheduled(cron = "0 0 3 * * *")
    @SchedulerLock(name = "accountDeletionScheduler.executeDeletions", lockAtMostFor = "PT2H", lockAtLeastFor = "PT1M")
    public void executeDeletions() {
        tenantDeletionService.executeDeletions();
    }
}
