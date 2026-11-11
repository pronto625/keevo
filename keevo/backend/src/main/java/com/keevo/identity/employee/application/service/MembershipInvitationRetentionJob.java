package com.keevo.identity.employee.application.service;

import com.keevo.identity.employee.domain.port.out.MembershipInvitationRetentionRepository;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;

/**
 * Story 19.11c (routed from review 19-6b, privacy) — nightly retention of the invitations that will never become a
 * membership: DECLINED / CANCELLED rows answered, and PENDING rows expired, more than
 * {@code keevo.invitation-retention.days} (default {@value #DEFAULT_DAYS}) days ago are deleted at 03:55 Douala time
 * ({@code keevo.invitation-retention.cron}). The 30-day block on re-inviting someone who refused (19.6b) stays
 * effective: the cut-off is always later. ACCEPTED rows are kept.
 *
 * <p>Complexity: one {@code DELETE} per run on the partial index of V79 — set-based, whatever the number of
 * companies. Runs only when scheduling is on ({@code keevo.scheduling.enabled}); one instance per tick (ShedLock).
 */
@Component
public class MembershipInvitationRetentionJob {

    private static final Logger log = LoggerFactory.getLogger(MembershipInvitationRetentionJob.class);

    static final int DEFAULT_DAYS = 90;
    /** The refusal block of 19.6b: the retention may never be shorter. */
    static final int MIN_DAYS = 31;

    private final MembershipInvitationRetentionRepository invitations;
    private final Clock clock;
    private final int days;

    public MembershipInvitationRetentionJob(MembershipInvitationRetentionRepository invitations, Clock clock,
                                            @Value("${keevo.invitation-retention.days:" + DEFAULT_DAYS + "}")
                                            int days) {
        if (days < MIN_DAYS) {
            throw new IllegalArgumentException("keevo.invitation-retention.days must be >= " + MIN_DAYS);
        }
        this.invitations = invitations;
        this.clock = clock;
        this.days = days;
    }

    @Scheduled(cron = "${keevo.invitation-retention.cron:0 55 3 * * *}", zone = "Africa/Douala")
    @SchedulerLock(name = "membershipInvitationRetentionJob.purge", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    public void scheduled() {
        try {
            purge();
        } catch (RuntimeException e) {
            log.error("Invitation retention: nightly run failed ({})", e.getClass().getSimpleName(), e);
        }
    }

    /** One run; returns the number of invitations deleted. */
    public int purge() {
        int deleted = invitations.purgeNeverMembershipsBefore(clock.instant().minus(Duration.ofDays(days)));
        log.info("Invitation retention: {} invitation(s) refused, cancelled or expired more than {} days ago deleted",
                deleted, days);
        return deleted;
    }
}
